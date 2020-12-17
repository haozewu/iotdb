/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iotdb.db.conf;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import org.apache.commons.io.FileUtils;
import org.apache.iotdb.db.conf.directories.DirectoryManager;
import org.apache.iotdb.db.engine.fileSystem.SystemFileFactory;
import org.apache.iotdb.db.engine.modification.ModificationFile;
import org.apache.iotdb.db.engine.storagegroup.TsFileResource;
import org.apache.iotdb.db.metadata.logfile.MLogWriter;
import org.apache.iotdb.tsfile.common.conf.TSFileConfig;
import org.apache.iotdb.tsfile.common.conf.TSFileDescriptor;
import org.apache.iotdb.tsfile.common.constant.TsFileConstant;
import org.apache.iotdb.tsfile.fileSystem.FSFactoryProducer;
import org.apache.iotdb.tsfile.fileSystem.fsFactory.FSFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IoTDBConfigCheck {

  private static final Logger logger = LoggerFactory.getLogger(IoTDBDescriptor.class);

  private static final IoTDBConfig config = IoTDBDescriptor.getInstance().getConfig();

  private FSFactory fsFactory = FSFactoryProducer.getFSFactory();

  // this file is located in data/system/schema/system.properties
  // If user delete folder "data", system.properties can reset.
  private static final String PROPERTIES_FILE_NAME = "system.properties";
  private static final String SCHEMA_DIR = config.getSchemaDir();
  private static final String WAL_DIR = config.getWalDir();

  private File propertiesFile;
  private File tmpPropertiesFile;

  private Properties properties = new Properties();

  private Map<String, String> systemProperties = new HashMap<>();

  private static final String SYSTEM_PROPERTIES_STRING = "System properties:";

  private static final String TIMESTAMP_PRECISION_STRING = "timestamp_precision";
  private static String timestampPrecision = config.getTimestampPrecision();

  private static final String PARTITION_INTERVAL_STRING = "partition_interval";
  private static long partitionInterval = config.getPartitionInterval();

  private static final String TSFILE_FILE_SYSTEM_STRING = "tsfile_storage_fs";
  private static String tsfileFileSystem = config.getTsFileStorageFs().toString();

  private static final String ENABLE_PARTITION_STRING = "enable_partition";
  private static boolean enablePartition = config.isEnablePartition();

  private static final String TAG_ATTRIBUTE_SIZE_STRING = "tag_attribute_total_size";
  private static String tagAttributeTotalSize = String.valueOf(config.getTagAttributeTotalSize());

  private static final String MAX_DEGREE_OF_INDEX_STRING = "max_degree_of_index_node";
  private static String maxDegreeOfIndexNode = String
      .valueOf(TSFileDescriptor.getInstance().getConfig().getMaxDegreeOfIndexNode());

  private static final String IOTDB_VERSION_STRING = "iotdb_version";

  public static IoTDBConfigCheck getInstance() {
    return IoTDBConfigCheckHolder.INSTANCE;
  }

  private static class IoTDBConfigCheckHolder {

    private static final IoTDBConfigCheck INSTANCE = new IoTDBConfigCheck();
  }

  private IoTDBConfigCheck() {
    logger.info("Starting IoTDB " + IoTDBConstant.VERSION);

    // check whether SCHEMA_DIR exists, create if not exists
    File dir = SystemFileFactory.INSTANCE.getFile(SCHEMA_DIR);
    if (!dir.exists()) {
      if (!dir.mkdirs()) {
        logger.error("can not create schema dir: {}", SCHEMA_DIR);
        System.exit(-1);
      } else {
        logger.info(" {} dir has been created.", SCHEMA_DIR);
      }
    }

    // check time stamp precision
    if (!(timestampPrecision.equals("ms") || timestampPrecision.equals("us")
        || timestampPrecision.equals("ns"))) {
      logger.error("Wrong {}, please set as: ms, us or ns ! Current is: {}",
          TIMESTAMP_PRECISION_STRING, timestampPrecision);
      System.exit(-1);
    }

    if (!enablePartition) {
      partitionInterval = Long.MAX_VALUE;
    }

    // check partition interval
    if (partitionInterval <= 0) {
      logger.error("Partition interval must larger than 0!");
      System.exit(-1);
    }

    systemProperties.put(IOTDB_VERSION_STRING, IoTDBConstant.VERSION);
    systemProperties.put(TIMESTAMP_PRECISION_STRING, timestampPrecision);
    systemProperties.put(PARTITION_INTERVAL_STRING, String.valueOf(partitionInterval));
    systemProperties.put(TSFILE_FILE_SYSTEM_STRING, tsfileFileSystem);
    systemProperties.put(ENABLE_PARTITION_STRING, String.valueOf(enablePartition));
    systemProperties.put(TAG_ATTRIBUTE_SIZE_STRING, tagAttributeTotalSize);
    systemProperties.put(MAX_DEGREE_OF_INDEX_STRING, maxDegreeOfIndexNode);
  }


  /**
   * check configuration in system.properties when starting IoTDB
   *
   * When init: create system.properties directly
   *
   * When upgrading the system.properties:
   * (1) create system.properties.tmp
   * (2) delete system.properties
   * (3) rename system.properties.tmp to system.properties
   */
  public void checkConfig() throws IOException {
    propertiesFile = SystemFileFactory.INSTANCE
        .getFile(IoTDBConfigCheck.SCHEMA_DIR + File.separator + PROPERTIES_FILE_NAME);
    tmpPropertiesFile = SystemFileFactory.INSTANCE
        .getFile(IoTDBConfigCheck.SCHEMA_DIR + File.separator + PROPERTIES_FILE_NAME + ".tmp");

    // system init first time, no need to check, write system.properties and return
    if (!propertiesFile.exists() && !tmpPropertiesFile.exists()) {
      // create system.properties
      if (propertiesFile.createNewFile()) {
        logger.info(" {} has been created.", propertiesFile.getAbsolutePath());
      } else {
        logger.error("can not create {}", propertiesFile.getAbsolutePath());
        System.exit(-1);
      }

      // write properties to system.properties
      try (FileOutputStream outputStream = new FileOutputStream(propertiesFile)) {
        systemProperties.forEach((k, v) -> properties.setProperty(k, v));
        properties.store(outputStream, SYSTEM_PROPERTIES_STRING);
      }
      return;
    }

    if (!propertiesFile.exists() && tmpPropertiesFile.exists()) {
      // rename tmp file to system.properties, no need to check
      FileUtils.moveFile(tmpPropertiesFile, propertiesFile);
      logger.info("rename {} to {}", tmpPropertiesFile, propertiesFile);
      return;
    } else if (propertiesFile.exists() && tmpPropertiesFile.exists()) {
      // both files exist, remove tmp file
      FileUtils.forceDelete(tmpPropertiesFile);
      logger.info("remove {}", tmpPropertiesFile);
    }

    // no tmp file, read properties from system.properties
    try (FileInputStream inputStream = new FileInputStream(propertiesFile);
        InputStreamReader inputStreamReader = new InputStreamReader(
            inputStream, TSFileConfig.STRING_CHARSET)) {
      properties.load(inputStreamReader);
    }
    // check whether upgrading from <=v0.10 to v0.12
    if (!properties.containsKey(IOTDB_VERSION_STRING) ||
      properties.getProperty(IOTDB_VERSION_STRING).startsWith("0.10")) {
      logger.error("DO NOT UPGRADE IoTDB from v0.10 or lower version to v0.12!"
          + " Please upgrade to v0.11 first");
      System.exit(-1);
    }

    // check whether upgrading from v0.11 to v0.12
    if (properties.getProperty(IOTDB_VERSION_STRING).startsWith("0.11")) {
      logger.info("Upgrading IoTDB from v0.11 to v0.12, checking files...");
      checkUnClosedTsFileV2();
      moveTsFileV2();
      upgradePropertiesFile();
      logger.info("Upgrade to IoTDB v0.12 successfully!");
      logger.info("Start upgrading Version-2 TsFiles...");
    }

    MLogWriter.upgradeMLog();

    logger.info("Mlog upgraded!");
    checkProperties();
  }

  /**
   * upgrade 0.11 properties to 0.12 properties
   */
  private void upgradePropertiesFile()
      throws IOException {
    // create an empty tmpPropertiesFile
    if (tmpPropertiesFile.createNewFile()) {
      logger.info("Create system.properties.tmp {}.", tmpPropertiesFile);
    } else {
      logger.error("Create system.properties.tmp {} failed.", tmpPropertiesFile);
      System.exit(-1);
    }

    try (FileOutputStream tmpFOS = new FileOutputStream(tmpPropertiesFile.toString())) {
      properties.setProperty(PARTITION_INTERVAL_STRING, String.valueOf(partitionInterval));
      properties.setProperty(TSFILE_FILE_SYSTEM_STRING, tsfileFileSystem);
      properties.setProperty(IOTDB_VERSION_STRING, IoTDBConstant.VERSION);
      properties.setProperty(ENABLE_PARTITION_STRING, String.valueOf(enablePartition));
      properties.setProperty(TAG_ATTRIBUTE_SIZE_STRING, tagAttributeTotalSize);
      properties.setProperty(MAX_DEGREE_OF_INDEX_STRING, maxDegreeOfIndexNode);
      properties.store(tmpFOS, SYSTEM_PROPERTIES_STRING);

      // upgrade finished, delete old system.properties file
      if (propertiesFile.exists()) {
        Files.delete(propertiesFile.toPath());
      }
    }
    // rename system.properties.tmp to system.properties
    FileUtils.moveFile(tmpPropertiesFile, propertiesFile);
  }

  /**
   * repair 0.10 properties
   */
  private void upgradePropertiesFileFromBrokenFile()
      throws IOException {
    // create an empty tmpPropertiesFile
    if (tmpPropertiesFile.createNewFile()) {
      logger.info("Create system.properties.tmp {}.", tmpPropertiesFile);
    } else {
      logger.error("Create system.properties.tmp {} failed.", tmpPropertiesFile);
      System.exit(-1);
    }

    try (FileOutputStream tmpFOS = new FileOutputStream(tmpPropertiesFile.toString())) {
      systemProperties.forEach((k, v) -> {
        if (!properties.containsKey(k)) {
          properties.setProperty(k, v);
        }
      });

      properties.store(tmpFOS, SYSTEM_PROPERTIES_STRING);
      // upgrade finished, delete old system.properties file
      if (propertiesFile.exists()) {
        Files.delete(propertiesFile.toPath());
      }
    }
    // rename system.properties.tmp to system.properties
    FileUtils.moveFile(tmpPropertiesFile, propertiesFile);
  }

  /**
   * Check all immutable properties
   */
  private void checkProperties() throws IOException {
    for (Entry<String, String> entry : systemProperties.entrySet()) {
      if (!properties.containsKey(entry.getKey())) {
        upgradePropertiesFileFromBrokenFile();
        logger.info("repair system.properties, lack {}", entry.getKey());
      }
    }

    if (!properties.getProperty(TIMESTAMP_PRECISION_STRING).equals(timestampPrecision)) {
      printErrorLogAndExit(TIMESTAMP_PRECISION_STRING);
    }

    if (Long.parseLong(properties.getProperty(PARTITION_INTERVAL_STRING)) != partitionInterval) {
      printErrorLogAndExit(PARTITION_INTERVAL_STRING);
    }

    if (!(properties.getProperty(TSFILE_FILE_SYSTEM_STRING).equals(tsfileFileSystem))) {
      printErrorLogAndExit(TSFILE_FILE_SYSTEM_STRING);
    }

    if (!(properties.getProperty(TAG_ATTRIBUTE_SIZE_STRING).equals(tagAttributeTotalSize))) {
      printErrorLogAndExit(TAG_ATTRIBUTE_SIZE_STRING);
    }

    if (!(properties.getProperty(MAX_DEGREE_OF_INDEX_STRING).equals(maxDegreeOfIndexNode))) {
      printErrorLogAndExit(MAX_DEGREE_OF_INDEX_STRING);
    }
  }

  private void printErrorLogAndExit(String property) {
    logger.error("Wrong {}, please set as: {} !", property, properties.getProperty(property));
    System.exit(-1);
  }

  /**
   * ensure all TsFiles are closed in 0.11 when starting 0.12
   */
  private void checkUnClosedTsFileV2() {
    if (SystemFileFactory.INSTANCE.getFile(WAL_DIR).isDirectory()
      && SystemFileFactory.INSTANCE.getFile(WAL_DIR).list().length != 0) {
      logger.error("Unclosed Version-2 TsFile detected, please stop insertion, then run 'flush' "
          + "on v0.11 IoTDB before upgrading to v0.12");
      System.exit(-1);
    }
    checkUnClosedTsFileV2InFolders(DirectoryManager.getInstance().getAllSequenceFileFolders());
    checkUnClosedTsFileV2InFolders(DirectoryManager.getInstance().getAllUnSequenceFileFolders());
  }

  private void checkUnClosedTsFileV2InFolders(List<String> folders) {
    for (String baseDir : folders) {
      File fileFolder = fsFactory.getFile(baseDir);
      if (!fileFolder.isDirectory()) {
        continue;
      }
      for (File storageGroup : fileFolder.listFiles()) {
        if (!storageGroup.isDirectory()) {
          continue;
        }
        for (File partitionDir : storageGroup.listFiles()) {
          if (!partitionDir.isDirectory()) {
            continue;
          }
          File[] tsfiles = fsFactory
              .listFilesBySuffix(partitionDir.toString(), TsFileConstant.TSFILE_SUFFIX);
          File[] resources = fsFactory
              .listFilesBySuffix(partitionDir.toString(), TsFileResource.RESOURCE_SUFFIX);
          if (tsfiles.length != resources.length) {
            logger.error("Unclosed Version-2 TsFile detected, please stop insertion, then run 'flush' "
                + "on v0.11 IoTDB before upgrading to v0.12");
            System.exit(-1);
          }
        }
      }
    }
  }

  private void moveTsFileV2() {
    moveFileToUpgradeFolder(DirectoryManager.getInstance().getAllSequenceFileFolders());
    moveFileToUpgradeFolder(DirectoryManager.getInstance().getAllUnSequenceFileFolders());
    logger.info("Move version-2 TsFile successfully");
  }

  private void moveFileToUpgradeFolder(List<String> folders) {
    for (String baseDir : folders) {
      File fileFolder = fsFactory.getFile(baseDir);
      if (!fileFolder.isDirectory()) {
        continue;
      }
      for (File storageGroup : fileFolder.listFiles()) {
        if (!storageGroup.isDirectory()) {
          continue;
        }
        File upgradeFolder = fsFactory.getFile(storageGroup, IoTDBConstant.UPGRADE_FOLDER_NAME);
        // create upgrade directory if not exist
        if (upgradeFolder.mkdirs()) {
          logger.info("Upgrade Directory {} doesn't exist, create it",
              upgradeFolder.getPath());
        } else if (!upgradeFolder.exists()) {
          logger.error("Create upgrade Directory {} failed",
              upgradeFolder.getPath());
        }
        for (File partitionDir : storageGroup.listFiles()) {
          if (!partitionDir.isDirectory() || 
              partitionDir.getName().equals(IoTDBConstant.UPGRADE_FOLDER_NAME)) {
            continue;
          }
          File[] oldTsfileArray = fsFactory
              .listFilesBySuffix(partitionDir.getAbsolutePath(), TsFileConstant.TSFILE_SUFFIX);
          File[] oldResourceFileArray = fsFactory
              .listFilesBySuffix(partitionDir.getAbsolutePath(), TsFileResource.RESOURCE_SUFFIX);
          File[] oldModificationFileArray = fsFactory
              .listFilesBySuffix(partitionDir.getAbsolutePath(), ModificationFile.FILE_SUFFIX);
          // move the old files to upgrade folder if exists
          if (oldTsfileArray.length != 0) {
            // create upgrade directory if not exist
            if (upgradeFolder.mkdirs()) {
              logger.info("Upgrade Directory {} doesn't exist, create it",
                  upgradeFolder.getPath());
            } else if (!upgradeFolder.exists()) {
              logger.error("Create upgrade Directory {} failed",
                  upgradeFolder.getPath());
            }
            // move .tsfile to upgrade folder
            for (File file : oldTsfileArray) {
              if (!file.renameTo(fsFactory.getFile(upgradeFolder, file.getName()))) {
                logger.error("Failed to move tsfile {} to upgrade folder", file);
                System.exit(-1);
              }
            }
            // move .resource to upgrade folder
            for (File file : oldResourceFileArray) {
              if (!file.renameTo(fsFactory.getFile(upgradeFolder, file.getName()))) {
                logger.error("Failed to move resource {} to upgrade folder", file);
                System.exit(-1);
              }
            }
            // move .mods to upgrade folder
            for (File file : oldModificationFileArray) {
              if (!file.renameTo(fsFactory.getFile(upgradeFolder, file.getName()))) {
                logger.error("Failed to move mod file {} to upgrade folder", file);
                System.exit(-1);
              }
            }
          }
        }
      }
    }
  }

}

