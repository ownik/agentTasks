package jetbrains.buildServer.agentsPlugin;

import jetbrains.buildServer.serverSide.SBuildAgent;
import jetbrains.buildServer.util.ItemProcessor;

import java.util.Date;

public interface TaskDescriptor {
  Date getScheduledTime();

  ItemProcessor<SBuildAgent> getAgentsProcessor();
}
