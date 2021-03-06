package org.batfish.main;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import javax.annotation.Nullable;
import org.apache.commons.collections4.map.LRUMap;
import org.batfish.common.BatfishException;
import org.batfish.common.BatfishLogger;
import org.batfish.common.BfConsts;
import org.batfish.common.util.CommonUtil;
import org.batfish.config.Settings;
import org.batfish.config.Settings.EnvironmentSettings;
import org.batfish.config.Settings.TestrigSettings;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.ConfigurationFormat;
import org.batfish.datamodel.DataPlane;
import org.batfish.datamodel.Interface;
import org.batfish.datamodel.collections.BgpAdvertisementsByVrf;
import org.batfish.datamodel.collections.RoutesByVrf;
import org.junit.rules.TemporaryFolder;

public class BatfishTestUtils {

  private static Batfish initBatfish(
      SortedMap<String, Configuration> configurations, @Nullable TemporaryFolder tempFolder)
      throws IOException {
    Settings settings = new Settings(new String[] {});
    settings.setLogger(new BatfishLogger("debug", false));
    final Map<TestrigSettings, SortedMap<String, Configuration>> CACHED_TESTRIGS =
        Collections.synchronizedMap(
            new LRUMap<TestrigSettings, SortedMap<String, Configuration>>(5));
    if (!configurations.isEmpty()) {
      Path containerDir = tempFolder.newFolder("container").toPath();
      settings.setContainerDir(containerDir);
      settings.setTestrig("tempTestrig");
      settings.setEnvironmentName("tempEnvironment");
      Batfish.initTestrigSettings(settings);
      settings.getBaseTestrigSettings().getSerializeIndependentPath().toFile().mkdirs();
      settings.getBaseTestrigSettings().getEnvironmentSettings().getEnvPath().toFile().mkdirs();
      CACHED_TESTRIGS.put(settings.getBaseTestrigSettings(), configurations);
      settings.setActiveTestrigSettings(settings.getBaseTestrigSettings());
    }

    final Map<TestrigSettings, DataPlane> CACHED_DATA_PLANES =
        Collections.synchronizedMap(new LRUMap<TestrigSettings, DataPlane>(2));
    final Map<EnvironmentSettings, SortedMap<String, BgpAdvertisementsByVrf>>
        CACHED_ENVIRONMENT_BGP_TABLES =
            Collections.synchronizedMap(
                new LRUMap<EnvironmentSettings, SortedMap<String, BgpAdvertisementsByVrf>>(4));
    final Map<EnvironmentSettings, SortedMap<String, RoutesByVrf>>
        CACHED_ENVIRONMENT_ROUTING_TABLES =
            Collections.synchronizedMap(
                new LRUMap<EnvironmentSettings, SortedMap<String, RoutesByVrf>>(4));
    Batfish batfish =
        new Batfish(
            settings,
            CACHED_TESTRIGS,
            CACHED_DATA_PLANES,
            CACHED_ENVIRONMENT_BGP_TABLES,
            CACHED_ENVIRONMENT_ROUTING_TABLES);
    return batfish;
  }

  private static Batfish initBatfishFromConfigurationText(
      SortedMap<String, String> configurationText, @Nullable TemporaryFolder tempFolder)
      throws IOException {
    Settings settings = new Settings(new String[] {});
    settings.setLogger(new BatfishLogger("debug", false));
    final Map<TestrigSettings, SortedMap<String, Configuration>> CACHED_TESTRIGS =
        Collections.synchronizedMap(
            new LRUMap<TestrigSettings, SortedMap<String, Configuration>>(5));
    Path containerDir = tempFolder.newFolder("container").toPath();
    settings.setContainerDir(containerDir);
    settings.setTestrig("tempTestrig");
    settings.setEnvironmentName("tempEnvironment");
    Batfish.initTestrigSettings(settings);
    Path testrigPath = settings.getBaseTestrigSettings().getTestRigPath();
    testrigPath.resolve(BfConsts.RELPATH_CONFIGURATIONS_DIR).toFile().mkdirs();
    testrigPath.resolve(BfConsts.RELPATH_AWS_VPC_CONFIGS_DIR).toFile().mkdirs();
    testrigPath.resolve(BfConsts.RELPATH_HOST_CONFIGS_DIR).toFile().mkdirs();
    settings.getBaseTestrigSettings().getEnvironmentSettings().getEnvPath().toFile().mkdirs();
    settings.setActiveTestrigSettings(settings.getBaseTestrigSettings());
    configurationText.forEach(
        (filename, content) -> {
          Path filePath =
              testrigPath.resolve(Paths.get(BfConsts.RELPATH_CONFIGURATIONS_DIR, filename));
          CommonUtil.writeFile(filePath, content);
        });

    final Map<TestrigSettings, DataPlane> CACHED_DATA_PLANES =
        Collections.synchronizedMap(new LRUMap<TestrigSettings, DataPlane>(2));
    final Map<EnvironmentSettings, SortedMap<String, BgpAdvertisementsByVrf>>
        CACHED_ENVIRONMENT_BGP_TABLES =
            Collections.synchronizedMap(
                new LRUMap<EnvironmentSettings, SortedMap<String, BgpAdvertisementsByVrf>>(4));
    final Map<EnvironmentSettings, SortedMap<String, RoutesByVrf>>
        CACHED_ENVIRONMENT_ROUTING_TABLES =
            Collections.synchronizedMap(
                new LRUMap<EnvironmentSettings, SortedMap<String, RoutesByVrf>>(4));
    Batfish batfish =
        new Batfish(
            settings,
            CACHED_TESTRIGS,
            CACHED_DATA_PLANES,
            CACHED_ENVIRONMENT_BGP_TABLES,
            CACHED_ENVIRONMENT_ROUTING_TABLES);
    batfish.serializeVendorConfigs(
        testrigPath, settings.getBaseTestrigSettings().getSerializeVendorPath());
    batfish.serializeIndependentConfigs(
        settings.getBaseTestrigSettings().getSerializeVendorPath(),
        settings.getBaseTestrigSettings().getSerializeIndependentPath());
    return batfish;
  }

  /**
   * Get a configuration object with the given interfaces
   *
   * @param nodeName Host name for the configuration
   * @param configFormat Configuration format
   * @param interfaceNames All interface names to be included
   * @return A new configuration
   */
  public static Configuration createTestConfiguration(
      String nodeName, ConfigurationFormat configFormat, String... interfaceNames) {
    Configuration config = new Configuration(nodeName);
    config.setConfigurationFormat(configFormat);
    for (String interfaceName : interfaceNames) {
      config.getInterfaces().put(interfaceName, new Interface(interfaceName, config));
    }
    return config;
  }

  /**
   * Get a new Batfish instance with given configurations, tempFolder should be present for
   * non-empty configurations
   *
   * @param configurations Map of all Configuration Name -> Configuration Object
   * @param tempFolder Temporary folder to be used to files required for Batfish
   * @return New Batfish instance
   */
  public static Batfish getBatfishFromConfigurationText(
      SortedMap<String, String> configurationText, @Nullable TemporaryFolder tempFolder)
      throws IOException {
    if (!configurationText.isEmpty() && tempFolder == null) {
      throw new BatfishException("tempFolder must be set for non-empty configurations");
    }
    return initBatfishFromConfigurationText(configurationText, tempFolder);
  }

  /**
   * Get a new Batfish instance with given configurations, tempFolder should be present for
   * non-empty configurations
   *
   * @param configurations Map of all Configuration Name -> Configuration Object
   * @param tempFolder Temporary folder to be used to files required for Batfish
   * @return New Batfish instance
   */
  public static Batfish getBatfish(
      SortedMap<String, Configuration> configurations, @Nullable TemporaryFolder tempFolder)
      throws IOException {
    if (!configurations.isEmpty() && tempFolder == null) {
      throw new BatfishException("tempFolder must be set for non-empty configurations");
    }
    return initBatfish(configurations, tempFolder);
  }
}
