/*
 * Copyright 2013-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.cli;

import com.facebook.buck.android.AndroidPlatformTarget;
import com.facebook.buck.apple.AppleConfig;
import com.facebook.buck.apple.AppleSdk;
import com.facebook.buck.apple.AppleSdkPaths;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.PackagedResource;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.Verbosity;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.io.Files;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

import javax.annotation.Nullable;

/**
 * This class creates a terminal command for Buck that creates a sample Buck project in the
 * directory the user specifies. It will fail if it cannot find the template directory, or if it is
 * unable to write to the destination directory.
 */
public class QuickstartCommand extends AbstractCommand {

  enum Type {
    ANDROID,
    IOS,
  }

  private static final ImmutableMap<Type, String> PATHS_TO_QUICKSTART_DIR =
      ImmutableMap.of(
          Type.ANDROID,
          "quickstart/android/android-template.zip",
          Type.IOS,
          "quickstart/ios/ios-template.zip");

  @Option(name = "--dest-dir", usage = "Destination project directory")
  private String destDir = "";

  @Nullable
  @Option(name = "--android-sdk", usage = "Android SDK directory")
  private String androidSdkDir;

  @Option(name = "--type", usage = "Type of project")
  private Type type = Type.ANDROID;

  @Argument
  private List<String> arguments = Lists.newArrayList();

  public List<String> getArguments() {
    return arguments;
  }

  @VisibleForTesting
  void setArguments(List<String> arguments) {
    this.arguments = arguments;
  }

  public String getDestDir() {
    return destDir;
  }

  public String getAndroidSdkDir(Supplier<AndroidPlatformTarget> platformTargetSupplier) {
    if (androidSdkDir == null) {
      try {
        Optional<Path> possibleSdkDir = platformTargetSupplier.get().getSdkDirectory();

        this.androidSdkDir = possibleSdkDir.isPresent() ?
            possibleSdkDir.get().toAbsolutePath().toString() :
            "";
      } catch (HumanReadableException e) {
        this.androidSdkDir = "";
      }
    }

    return androidSdkDir;
  }

  /**
   * Runs the command "buck quickstart", which copies a template project into a new directory to
   * give the user a functional buck project. It copies from
   * src/com/facebook/buck/cli/quickstart/android to the directory specified. It then asks the user
   * for the location of the Android SDK so Buck can successfully build the quickstart project. It
   * will fail if it cannot find the template directory or if it is unable to write to the
   * destination directory.
   *
   * @return status code - zero means no problem
   * @throws IOException if the command fails to read from the template project or write to the
   *     new project
   */
  @Override
  public int runWithoutHelp(CommandRunnerParams params) throws IOException {
    String projectDir = getDestDir().trim();
    if (projectDir.isEmpty()) {
      projectDir = promptForPath(
          params,
          "Enter the directory where you would like to create the project: ");
    }

    File dir = new File(projectDir);
    while (!dir.isDirectory() && !dir.mkdirs() && !projectDir.isEmpty()) {
      projectDir = promptForPath(
          params,
          "Cannot create project directory. Enter another directory: ");
      dir = new File(projectDir);
    }
    if (projectDir.isEmpty()) {
      params.getBuckEventBus().post(ConsoleEvent.severe(
          "No project directory specified. Aborting quickstart."));
      return 1;
    }

    PackagedResource resource =
        new PackagedResource(
            params.getCell().getFilesystem(),
            QuickstartCommand.class,
            Preconditions.checkNotNull(PATHS_TO_QUICKSTART_DIR.get(type)));
    Path origin = resource.get();

    final Path destination = Paths.get(projectDir);

    switch (type) {
      case ANDROID: {
        String sdkLocation = getAndroidSdkDir(params.getAndroidPlatformTargetSupplier());
        if (sdkLocation.isEmpty()) {
          sdkLocation = promptForPath(params, "Enter your Android SDK's location: ");
        }

        File sdkLocationFile = new File(sdkLocation);
        if (!sdkLocationFile.isDirectory()) {
          params.getBuckEventBus().post(ConsoleEvent.severe(
              "WARNING: That Android SDK directory does not exist."));
        }

        sdkLocation = sdkLocationFile.getAbsoluteFile().toString();

        params.getCell().getFilesystem().copyFolder(origin, destination);

        // Specify the default Android target so everyone on the project builds against the same
        // SDK.
        File buckConfig = new File(projectDir + "/.buckconfig");
        Files.append(
            "[android]\n    target = " + AndroidPlatformTarget.DEFAULT_ANDROID_PLATFORM_TARGET +
                "\n",
            buckConfig,
            StandardCharsets.UTF_8);

        File localProperties = new File(projectDir + "/local.properties");
        Files.write("sdk.dir=" + sdkLocation + "\n", localProperties, StandardCharsets.UTF_8);
        break;
      }

      case IOS: {
        ImmutableMap<AppleSdk, AppleSdkPaths> appleSdkPaths = null;
        try (
            PrintStream stdout = new PrintStream(new ByteArrayOutputStream());
            PrintStream stderr = new PrintStream(new ByteArrayOutputStream())) {
          AppleConfig appleConfig = new AppleConfig(params.getBuckConfig());
          appleSdkPaths = appleConfig.getAppleSdkPaths(
              new ProcessExecutor(
                  new Console(Verbosity.SILENT, stdout, stderr, Ansi.withoutTty())));
        }
        if (appleSdkPaths == null || appleSdkPaths.isEmpty()) {
          throw new HumanReadableException(
              "Could not find any Apple SDK, check your Xcode installation.");
        }

        params.getCell().getFilesystem().copyFolder(origin, destination);

        File buckConfig = new File(projectDir + "/.buckconfig");
        Files.append("\n", buckConfig, StandardCharsets.UTF_8);

        break;
      }
    }

    java.nio.file.Files.walkFileTree(destination, new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(
              Path file, BasicFileAttributes attrs) throws IOException {
            if ("BUCK.tmpl".equals(file.getFileName().toString())) {
              Path actualBuck = file.getParent().resolve("BUCK");
              java.nio.file.Files.copy(file, actualBuck);
              java.nio.file.Files.delete(file);
            }
            return FileVisitResult.CONTINUE;
          }
        });

    params.getConsole().getStdOut().print(
        params.getCell().getFilesystem().readFileIfItExists(origin.resolve("README.md")).get());
    params.getConsole().getStdOut().flush();

    return 0;
  }

  @Override
  public boolean isReadOnly() {
    return false;
  }

  private String promptForPath(CommandRunnerParams params, String prompt) throws IOException {
    params.getConsole().getStdOut().print(prompt);
    params.getConsole().getStdOut().flush();
    BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
    String path = br.readLine();
    if (path != null) {
      return expandTilde(path.trim());
    }
    return "";
  }

  /**
   * A simple function to convert "~" to the home directory (i.e. /home/user) in paths. It does not
   * support the shortcut for other users' home directories, "~user". If the path does not start
   * with "~/", then this function returns the same string it was given.
   *
   * @param path an absolute path string that begins with "~/"
   * @return an absolute path with an expanded home directory
   */
  protected static String expandTilde(String path) {
    return expandTildeInternal(System.getProperty("user.home"), path);
  }

  @VisibleForTesting
  static String expandTildeInternal(String homeDir, String path) {
    if (path.startsWith("~/")) {
      return homeDir + path.substring(1);
    } else if (path.equals("~")) {
      return homeDir;
    } else {
      return path;
    }
  }

  @Override
  public String getShortDescription() {
    return "generates a default project directory";
  }

}
