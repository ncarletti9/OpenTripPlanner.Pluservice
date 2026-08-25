package org.opentripplanner.logging;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;
import ch.qos.logback.core.util.FileSize;
import java.io.File;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LogbackConfigurator {

  private static final Logger LOG = LoggerFactory.getLogger(LogbackConfigurator.class);

  public static void configure(String directoryName) {
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    setupLoggerForGraph(context, directoryName);
  }

  private static void setupLoggerForGraph(LoggerContext context, String directoryName) {
    String logDirPath = "log/log_" + directoryName;
    File logDir = new File(logDirPath);
    if (!logDir.exists()) {
      logDir.mkdirs(); // Create the directory if it doesn't exist
      LOG.info("Created log directory: {}", logDirPath);
    } else {
      LOG.info("Log directory already exists: {}", logDirPath);
    }

    RollingFileAppender<ILoggingEvent> fileAppender = new RollingFileAppender<>();
    fileAppender.setContext(context);
    fileAppender.setName("FILE-" + directoryName);
    fileAppender.setFile(logDirPath + "/latest-log.txt");

    PatternLayoutEncoder encoder = new PatternLayoutEncoder();
    encoder.setContext(context);
    encoder.setPattern("%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} %msg%n");
    encoder.start();

    TimeBasedRollingPolicy<ILoggingEvent> rollingPolicy = new TimeBasedRollingPolicy<>();
    rollingPolicy.setContext(context);
    rollingPolicy.setParent(fileAppender);
    rollingPolicy.setFileNamePattern(logDirPath + "/OTPlog-%d{yyyy-MM-dd}.txt");
    rollingPolicy.setMaxHistory(30);
    rollingPolicy.setTotalSizeCap(FileSize.valueOf("2GB"));
    rollingPolicy.start();

    fileAppender.setEncoder(encoder);
    fileAppender.setRollingPolicy(rollingPolicy);
    fileAppender.start();

    ch.qos.logback.classic.Logger graphLogger = context.getLogger("GraphLogger-" + directoryName);
    graphLogger.addAppender(fileAppender);
    graphLogger.setLevel(ch.qos.logback.classic.Level.DEBUG);
    graphLogger.setAdditive(false); // Avoid double logging if you don't want it to be additive

    // Add the appender to the root logger as well
    ch.qos.logback.classic.Logger rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);
    rootLogger.addAppender(fileAppender);

    LOG.info("Configured logger for directory: {}", directoryName);
  }
}
