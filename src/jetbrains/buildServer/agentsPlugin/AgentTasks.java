package jetbrains.buildServer.agentsPlugin;

import jetbrains.buildServer.configuration.ChangeListener;
import jetbrains.buildServer.configuration.FileWatcher;
import jetbrains.buildServer.log.Loggers;
import jetbrains.buildServer.serverSide.BuildAgentManager;
import jetbrains.buildServer.serverSide.SBuildAgent;
import jetbrains.buildServer.serverSide.ServerPaths;
import jetbrains.buildServer.util.Dates;
import jetbrains.buildServer.util.FileUtil;
import jetbrains.buildServer.util.ItemProcessor;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class AgentTasks {
  public static final String CONFIG_FILE_NAME = "agentTasks.properties";
  private List<Future> myTasks = new ArrayList<Future>();
  private final ScheduledExecutorService myExecutorService;
  private final BuildAgentManager myAgentManager;

  public AgentTasks(ServerPaths serverPaths, ScheduledExecutorService executorService, BuildAgentManager agentManager) {
    myAgentManager = agentManager;
    myExecutorService = executorService;

    final File configFile = new File(serverPaths.getConfigDir(), CONFIG_FILE_NAME);
    loadConfiguration(configFile);

    FileWatcher configFileWatcher = new FileWatcher(configFile);
    configFileWatcher.start();
    configFileWatcher.registerListener(new ChangeListener() {
      public void changeOccured(String s) {
        loadConfiguration(configFile);
      }
    });
  }

  // agents.task.N=<HH:mm>,<enable|disable>,<agent names regexp>
  
  private void loadConfiguration(File configFile) {
    Properties props = new Properties();

    if (configFile.isFile()) {
      Loggers.SERVER.info("Loading configuration from file: " + configFile.getAbsolutePath());
      FileInputStream fis = null;
      try {
        fis = new FileInputStream(configFile);
        props.load(fis);
      } catch (IOException e) {
        Loggers.SERVER.warn("Failed to load configuration from file: " + configFile.getAbsolutePath() + ", error: " + e.toString());
      } finally {
        FileUtil.close(fis);
      }
    }

    List<TaskDescriptor> tasks = new ArrayList<TaskDescriptor>();
    
    for (String p: props.stringPropertyNames()) {
      String taskDef = (String) props.get(p);
      String[] splitted = taskDef.split(",");
      try {
        String[] scheduleOption = splitted[0].split(" ");
        String[] timeStr = scheduleOption[0].split(":");
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(timeStr[0]));
        c.set(Calendar.MINUTE, Integer.parseInt(timeStr[1]));
        c.set(Calendar.SECOND, 0);
        final Date time = c.getTime();
        final String taskName = splitted[1];
        final Pattern regexp = Pattern.compile(splitted[2]);
        final HashSet<Integer> scheduledDaysOfWeeks = parseScheduledDaysOfWeeks(scheduleOption);

        Loggers.SERVER.info("Loaded task: " + taskName + " agents matching " + splitted[2] + " on " + splitted[0]);

        TaskDescriptor td = new TaskDescriptor() {
          public Date getScheduledTime() {
            return time;
          }

          public ItemProcessor<SBuildAgent> getAgentsProcessor() {
            return new ItemProcessor<SBuildAgent>() {
              public boolean processItem(SBuildAgent sBuildAgent) {
                int dayOfWeek = Calendar.getInstance().get(Calendar.DAY_OF_WEEK);
                if (scheduledDaysOfWeeks.contains(dayOfWeek)
                        && regexp.matcher(sBuildAgent.getName()).find()) {
                  if ("enable".equals(taskName)) {
                    sBuildAgent.setEnabled(true, null, "Enabled by agent tasks plugin");
                  }
                  if ("disable".equals(taskName)) {
                    sBuildAgent.setEnabled(false, null, "Disabled by agent tasks plugin");
                  }
                }
                return true;
              }
            };
          }
        };
        tasks.add(td);
      } catch (Exception e) {
        Loggers.SERVER.warn("Failed to load task definition: " + taskDef + ", error: " + e.toString());
      }
    }
    
    scheduleTasks(tasks);
  }

  private HashSet<Integer> parseScheduledDaysOfWeeks(String[] options) {
    HashSet<Integer> scheduledDaysOfWeeks = new HashSet<Integer>();

    if(options.length <= 1) {
      scheduledDaysOfWeeks.add(Calendar.MONDAY);
      scheduledDaysOfWeeks.add(Calendar.TUESDAY);
      scheduledDaysOfWeeks.add(Calendar.WEDNESDAY);
      scheduledDaysOfWeeks.add(Calendar.THURSDAY);
      scheduledDaysOfWeeks.add(Calendar.FRIDAY);
      scheduledDaysOfWeeks.add(Calendar.SATURDAY);
      scheduledDaysOfWeeks.add(Calendar.SUNDAY);
    }
    else {
      for (int i = 1; i < options.length; ++i) {
        String str = options[i];

        if (str.toLowerCase().equals("mon")) {
          scheduledDaysOfWeeks.add(Calendar.MONDAY);
        } else if (str.toLowerCase().equals("tue")) {
          scheduledDaysOfWeeks.add(Calendar.TUESDAY);
        } else if (str.toLowerCase().equals("wed")) {
          scheduledDaysOfWeeks.add(Calendar.WEDNESDAY);
        } else if (str.toLowerCase().equals("thu")) {
          scheduledDaysOfWeeks.add(Calendar.THURSDAY);
        } else if (str.toLowerCase().equals("fri")) {
          scheduledDaysOfWeeks.add(Calendar.FRIDAY);
        } else if (str.toLowerCase().equals("sat")) {
          scheduledDaysOfWeeks.add(Calendar.SATURDAY);
        } else if (str.toLowerCase().equals("sun")) {
          scheduledDaysOfWeeks.add(Calendar.SUNDAY);
        }
      }
    }

    return scheduledDaysOfWeeks;
  }


  private synchronized void scheduleTasks(List<TaskDescriptor> tasks) {
    for (Future t: myTasks) t.cancel(true);

    myTasks.clear();

    for (final TaskDescriptor td: tasks) {
      Date now = Dates.now();
      Date scheduled = td.getScheduledTime();
      long diff = scheduled.getTime() - now.getTime();
      if (diff < 0) diff += Dates.ONE_DAY;
      
      final long delay = diff;
      myTasks.add(myExecutorService.scheduleAtFixedRate(new Runnable() {
        public void run() {
          Set<SBuildAgent> allAgents = new HashSet<SBuildAgent>(myAgentManager.getRegisteredAgents());
          allAgents.addAll(myAgentManager.getUnregisteredAgents());
          for (SBuildAgent agent : allAgents) {
            boolean result = td.getAgentsProcessor().processItem(agent);
            if (!result) break; // stop processing if item processor asked for this
          }
        }
      }, delay, Dates.ONE_DAY, TimeUnit.MILLISECONDS)); // run task every day
    }
  }
}
