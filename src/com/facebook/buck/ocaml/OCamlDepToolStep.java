/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.ocaml;

import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.base.Functions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;
import java.util.List;

import javax.annotation.Nullable;

/**
 * This step runs ocamldep tool to compute dependencies among source files (*.mli and *.ml)
 */
public class OCamlDepToolStep extends ShellStep {

  private final ImmutableList<SourcePath> input;
  private final ImmutableList<String> flags;
  private final SourcePathResolver resolver;
  private Tool ocamlDepTool;

  public OCamlDepToolStep(
      Path workingDirectory,
      SourcePathResolver resolver,
      Tool ocamlDepTool,
      List<SourcePath> input,
      List<String> flags) {
    super(workingDirectory);
    this.resolver = resolver;
    this.ocamlDepTool = ocamlDepTool;
    this.flags = ImmutableList.copyOf(flags);
    this.input = ImmutableList.copyOf(input);
  }

  @Override
  public String getShortName() {
    return "OCaml dependency";
  }

  @Override
  protected void addOptions(
      ExecutionContext context,
      ImmutableSet.Builder<ProcessExecutor.Option> options) {
    // We need this else we get output with color codes which confuses parsing
    options.add(ProcessExecutor.Option.EXPECTING_STD_ERR);
    options.add(ProcessExecutor.Option.EXPECTING_STD_OUT);
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(@Nullable ExecutionContext context) {
    return ImmutableList.<String>builder()
        .addAll(ocamlDepTool.getCommandPrefix(resolver))
        .add("-one-line")
        .add("-native")
        .addAll(flags)
        .addAll(FluentIterable.from(input)
            .transform(resolver.getAbsolutePathFunction())
            .transform(Functions.toStringFunction()))
        .build();
  }
}
