package org.sonar.server.computation;

import java.io.File;
import java.io.IOException;
import org.apache.commons.io.FileUtils;
import org.h2.util.IOUtils;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.sonar.api.config.Settings;
import org.sonar.process.ProcessProperties;

import static org.assertj.core.api.Assertions.assertThat;

public class ReportFilesTest {

  @Rule
  public TemporaryFolder temp = new TemporaryFolder();

  File reportDir;
  Settings settings = new Settings();
  ReportFiles underTest = new ReportFiles(settings);

  @Before
  public void setUp() throws IOException {
    File dataDir = temp.newFolder();
    reportDir = new File(dataDir, "ce/reports");
    settings.setProperty(ProcessProperties.PATH_DATA, dataDir.getCanonicalPath());
  }

  @Test
  public void save_report() throws IOException {
    underTest.save("TASK_1", IOUtils.getInputStreamFromString("{binary}"));

    assertThat(FileUtils.readFileToString(new File(reportDir, "TASK_1.zip"))).isEqualTo("{binary}");

  }

  @Test
  public void deleteIfExists_uuid_does_not_exist() {
    // do not fail, does nothing
    underTest.deleteIfExists("TASK_1");
  }

  @Test
  public void deleteIfExists() throws IOException {
    File report = new File(reportDir, "TASK_1.zip");
    FileUtils.touch(report);
    assertThat(report).exists();

    underTest.deleteIfExists("TASK_1");
    assertThat(report).doesNotExist();
  }

  /**
   * List the zip files contained in the report directory
   */
  @Test
  public void listUuids() throws IOException {
    FileUtils.touch(new File(reportDir, "TASK_1.zip"));
    FileUtils.touch(new File(reportDir, "TASK_2.zip"));
    FileUtils.touch(new File(reportDir, "something.else"));

    assertThat(underTest.listUuids()).containsOnly("TASK_1", "TASK_2");
  }

  @Test
  public void listUuids_dir_does_not_exist_yet() throws IOException {
    FileUtils.deleteQuietly(reportDir);

    assertThat(underTest.listUuids()).isEmpty();
  }

  @Test
  public void deleteAll() throws IOException {
    FileUtils.touch(new File(reportDir, "TASK_1.zip"));
    FileUtils.touch(new File(reportDir, "TASK_2.zip"));
    FileUtils.touch(new File(reportDir, "something.else"));

    underTest.deleteAll();

    // directory still exists but is empty
    assertThat(reportDir).exists().isDirectory();
    assertThat(reportDir.listFiles()).isEmpty();
  }

  @Test
  public void deleteAll_dir_does_not_exist_yet() throws IOException {
    FileUtils.deleteQuietly(reportDir);

    underTest.deleteAll();

    assertThat(reportDir).doesNotExist();
  }
}
