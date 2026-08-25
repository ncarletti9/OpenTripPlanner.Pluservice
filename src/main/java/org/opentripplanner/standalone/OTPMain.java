package org.opentripplanner.standalone;

import static org.opentripplanner.model.projectinfo.OtpProjectInfo.projectInfo;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import com.beust.jcommander.JCommander;
import com.beust.jcommander.ParameterException;
import java.io.*;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import org.geotools.referencing.factory.DeferredAuthorityFactory;
import org.geotools.util.WeakCollectionCleaner;
import org.opentripplanner.datastore.api.DataSource;
import org.opentripplanner.framework.application.ApplicationShutdownSupport;
import org.opentripplanner.framework.application.OtpAppException;
import org.opentripplanner.graph_builder.GraphBuilder;
import org.opentripplanner.graph_builder.issue.api.DataImportIssueSummary;
import org.opentripplanner.logging.LogbackConfigurator;
import org.opentripplanner.raptor.configure.RaptorConfig;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.graph.SerializedGraphObject;
import org.opentripplanner.standalone.config.CommandLineParameters;
import org.opentripplanner.standalone.configure.ConstructApplication;
import org.opentripplanner.standalone.configure.LoadApplication;
import org.opentripplanner.standalone.server.GrizzlyServer;
import org.opentripplanner.transit.service.TransitModel;
import org.opentripplanner.updater.configure.UpdaterConfigurator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

/**
 * This is the main entry point to OpenTripPlanner. It allows both building graphs and starting up
 * an OTP server depending on command line options. OTPMain is a concrete class making it possible
 * to construct one with custom CommandLineParameters and use its graph builder construction method
 * from web services or scripts, not just from the static main function below.
 */
public class OTPMain {

  private static final Logger LOG = LoggerFactory.getLogger(OTPMain.class);

  static {
    // Disable HSQLDB reconfiguration of Java Unified Logging (j.u.l)
    //noinspection AccessOfSystemProperties
    System.setProperty("hsqldb.reconfig_logging", "false");

    // Remove existing handlers attached to the j.u.l root logger
    SLF4JBridgeHandler.removeHandlersForRootLogger();
    // Bridge j.u.l (used by Jersey) to the SLF4J root logger, so all logging goes through the same API
    SLF4JBridgeHandler.install();
  }

  /**
   * ENTRY POINT: This is the main method that is called when running otp.jar from the command
   * line.
   */
  public static void main(String[] args) {
    // Determine if the application is running in debug mode
    boolean isDebug =
      java.lang.management.ManagementFactory
        .getRuntimeMXBean()
        .getInputArguments()
        .toString()
        .indexOf("-agentlib:jdwp") >
      0;

    // Initialize logging configuration with a specific directory name
    CommandLineParameters params = parseAndValidateCmdLine(args);
    LogbackConfigurator.configure(params.getBaseDirectory().getName());

    // Set the log level based on the debug mode
    Level level = Level.toLevel(isDebug ? "DEBUG" : "INFO");
    LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
    List<ch.qos.logback.classic.Logger> loggerList = loggerContext.getLoggerList();
    loggerList.forEach(tmpLogger -> tmpLogger.setLevel(level));
    LOG.info("Change log level to {}", level);

    try {
      Thread.currentThread().setName("main");
      OtpStartupInfo.logInfo();
      startOTPServer(params);
    } catch (OtpAppException ae) {
      LOG.error(ae.getMessage(), ae);
      System.exit(100);
    } catch (Exception e) {
      LOG.error("An uncaught error occurred inside OTP: {}", e.getLocalizedMessage(), e);
      System.exit(-1);
    }
  }

  /**
   * Parse and validate command line parameters. If the arguments is invalid the method uses {@code
   * System.exit()} to exit the application.
   */
  private static CommandLineParameters parseAndValidateCmdLine(String[] args) {
    CommandLineParameters params = new CommandLineParameters();
    try {
      // It is tempting to use JCommander's command syntax: http://jcommander.org/#_more_complex_syntaxes_commands
      // But this seems to lead to confusing switch ordering and more difficult subsequent use of the
      // parsed commands, since there will be three separate objects.
      JCommander jc = JCommander.newBuilder().addObject(params).args(args).build();
      if (params.version) {
        System.out.println("OpenTripPlanner " + projectInfo().getVersionString());
        System.exit(0);
      }
      if (params.serializationVersionId) {
        System.out.println(projectInfo().getOtpSerializationVersionId());
        System.exit(0);
      }
      if (params.help) {
        System.out.println("OpenTripPlanner " + projectInfo().getVersionString());
        jc.setProgramName("java -Xmx4G -jar otp.jar");
        jc.usage();
        System.exit(0);
      }
      params.inferAndValidate(!params.killExisting);
    } catch (ParameterException pex) {
      LOG.error("Parameter error: {}", pex.getMessage());
      System.exit(1);
    }
    return params;
  }

  /**
   * All startup logic is in an instance method instead of the static main method so it is possible
   * to build graphs from web services or scripts, not just from the command line. If options cause
   * an OTP API server to start up, this method will return when the web server shuts down.
   *
   * @throws RuntimeException if an error occurs while loading the graph.
   */
  private static void startOTPServer(CommandLineParameters cli) {
    boolean graphAvailable = false;
    LOG.info(
      "Searching for configuration and input files in {}",
      cli.getBaseDirectory().getAbsolutePath()
    );

    // Init loading phase (Separate DI scope)
    var loadApp = new LoadApplication(cli);
    var config = loadApp.config();

    // Validate data sources, command line arguments and config before loading and
    // processing input data to fail early
    loadApp.validateConfigAndDataSources();

    ConstructApplication app;

    /* Load graph from disk if one is not present from build. */
    if (cli.doLoadGraph() || cli.doLoadStreetGraph()) {
      SerializedGraphObject obj = SerializedGraphObject.load(loadApp.getInputGraphDataStore());
      app = loadApp.appConstruction(obj);
      config.updateConfigFromSerializedGraph(obj.buildConfig, obj.routerConfig);
      graphAvailable = true;

      obj.graph.routerId = cli.getBaseDirectory().getName();
      obj.graph.setBuildTime(loadApp.getInputGraphDataStore().lastModified());
      LOG.debug("graph build time: {}", obj.graph.getBuildTime());
    } else {
      app = loadApp.appConstruction();
    }

    /* Start graph builder if requested. */
    if (cli.doBuildStreet() || cli.doBuildTransit()) {
      // Abort building a graph if the file can not be saved
      SerializedGraphObject.verifyTheOutputGraphIsWritableIfDataSourceExist(
        app.graphOutputDataSource()
      );

      GraphBuilder graphBuilder = app.createGraphBuilder();
      if (graphBuilder != null) {
        graphBuilder.run();
        graphAvailable = true;
      } else {
        throw new IllegalStateException("An error occurred while building the graph.");
      }
      // Store graph and config used to build it, also store router-config for easy deployment
      // with using the embedded router config.
      new SerializedGraphObject(
        app.graph(),
        app.transitModel(),
        app.worldEnvelopeRepository(),
        config.buildConfig(),
        config.routerConfig(),
        DataImportIssueSummary.combine(graphBuilder.issueSummary(), app.dataImportIssueSummary()),
        app.emissionsDataModel(),
        app.stopConsolidationRepository(),
        app.streetLimitationParameters()
      )
        .save(app.graphOutputDataSource());
      // Log size info for the deduplicator
      LOG.info("Memory optimized {}", app.graph().deduplicator.toString());
    }

    // Load graph from disk if not present from build
    if (!graphAvailable && (cli.doLoadGraph() || cli.doLoadStreetGraph())) {
      SerializedGraphObject obj = SerializedGraphObject.load(loadApp.getInputGraphDataStore());
      app = loadApp.appConstruction(obj);
      config.updateConfigFromSerializedGraph(obj.buildConfig, obj.routerConfig);
      graphAvailable = true;
    }

    if (!graphAvailable) {
      LOG.error("Nothing to do, no graph loaded or build. Exiting.");
      System.exit(101);
    }

    if (cli.doServe()) {
      startOtpWebServer(cli, app);
    } else {
      LOG.info("Done building graph. Exiting.");
    }
  }

  private static void startOtpWebServer(CommandLineParameters params, ConstructApplication app) {
    // Index graph for travel search
    app.transitModel().index();
    app.graph().index(app.transitModel().getStopModel());

    // publishing the config version info make it available to the APIs
    setOtpConfigVersionsOnServerInfo(app);
    var configModel = app.getFactory().config();
    /* Start visualizer if requested. */
    if (params.visualize) {
      app.graphVisualizer().run();
    }

    /* Start web server if requested. */
    // We could start the server first so it can report build/load progress to a load balancer.
    // This would also avoid the awkward call to set the router on the appConstruction after it's constructed.
    // However, currently the server runs in a blocking way and waits for shutdown, so has to run last.
    if (params.doServe()) {
      // kill existing process using port
      if (params.killExisting) {
        try {
          String outLine, searchPort = String.format("0.0.0.0:%s", params.port);
          Process p = Runtime.getRuntime().exec("netstat -aon");
          BufferedReader input = new BufferedReader(new InputStreamReader(p.getInputStream()));
          while ((outLine = input.readLine()) != null) {
            List<String> outWords = Arrays.asList(outLine.split("[ ]"));

            if (outWords.contains("LISTENING") && outWords.contains(searchPort)) {
              String pid = outWords.get(outWords.size() - 1);
              LOG.info("killing process PID: {}", pid);
              Process kill = Runtime.getRuntime().exec("taskkill /f /pid " + pid);
              while (kill.isAlive()) {
                Thread.sleep(100);
              }
              if (kill.exitValue() != 0) LOG.error(
                "An error {} occurred when try kill process PID {}",
                kill.exitValue(),
                pid
              );
            }
          }
          input.close();
        } catch (Exception err) {
          err.printStackTrace();
        }
      }

      GrizzlyServer grizzlyServer = app.createGrizzlyServer();

      registerShutdownHookToGracefullyShutDownServer(app.transitModel(), app.raptorConfig());

      // Loop to restart server on uncaught fatal exceptions.
      while (true) {
        try {
          grizzlyServer.run();
          return;
        } catch (Throwable throwable) {
          LOG.error(
            "An uncaught error occurred inside OTP. Restarting server. Error was: {}",
            throwable.getMessage(),
            throwable
          );
        }
      }
    }
  }

  /**
   * Shut down this server when evicted or (auto-)reloaded.
   * <ol>
   *   <li>Stop any real-time updater threads.</li>
   *   <li>Cleanup various stuff of some used libraries (org.geotools), which depend on the
   *   external client to call them for cleaning-up.</li>
   * </ol>
   */
  private static void registerShutdownHookToGracefullyShutDownServer(
    TransitModel transitModel,
    RaptorConfig<?> raptorConfig
  ) {
    ApplicationShutdownSupport.addShutdownHook(
      "server-shutdown",
      () -> {
        LOG.info("OTP shutdown started...");
        UpdaterConfigurator.shutdownGraph(transitModel);
        raptorConfig.shutdown();
        WeakCollectionCleaner.DEFAULT.exit();
        DeferredAuthorityFactory.exit();
        LOG.info("OTP shutdown: resources released...");
      }
    );
  }

  private static void setOtpConfigVersionsOnServerInfo(ConstructApplication app) {
    projectInfo().otpConfigVersion = app.otpConfig().configVersion;
    projectInfo().buildConfigVersion = app.buildConfig().configVersion;
    projectInfo().routerConfigVersion = app.routerConfig().getConfigVersion();
  }
}
