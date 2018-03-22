/*
 * Copyright 2017-present Facebook, Inc.
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

import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.rules.HashedFileTool;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.toolchain.ToolchainCreationContext;
import com.facebook.buck.toolchain.ToolchainFactory;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.function.BiFunction;

public class OcamlToolchainFactory implements ToolchainFactory<OcamlToolchain> {

  private static final String SECTION = "ocaml";

  private static final Path DEFAULT_OCAML_COMPILER = Paths.get("ocamlopt.opt");
  private static final Path DEFAULT_OCAML_BYTECODE_COMPILER = Paths.get("ocamlc.opt");
  private static final Path DEFAULT_OCAML_DEP_TOOL = Paths.get("ocamldep.opt");
  private static final Path DEFAULT_OCAML_YACC_COMPILER = Paths.get("ocamlyacc");
  private static final Path DEFAULT_OCAML_DEBUG = Paths.get("ocamldebug");
  private static final Path DEFAULT_OCAML_LEX_COMPILER = Paths.get("ocamllex.opt");

  @Override
  public Optional<OcamlToolchain> createToolchain(
      ToolchainProvider toolchainProvider, ToolchainCreationContext context) {
    BiFunction<String, Path, Optional<Tool>> getTool =
        (field, defaultValue) ->
            context
                .getExecutableFinder()
                .getOptionalExecutable(
                    context.getBuckConfig().getPath(SECTION, field).orElse(defaultValue),
                    context.getBuckConfig().getEnvironment())
                .map(
                    path ->
                        new HashedFileTool(() -> context.getBuckConfig().getPathSourcePath(path)));
    CxxPlatform cxxPlatform =
        toolchainProvider
            .getByName(CxxPlatformsProvider.DEFAULT_NAME, CxxPlatformsProvider.class)
            .getDefaultCxxPlatform();
    return Optional.of(
        OcamlToolchain.of(
            OcamlPlatform.builder()
                .setOcamlCompiler(getTool.apply("ocaml.compiler", DEFAULT_OCAML_COMPILER))
                .setOcamlDepTool(getTool.apply("dep.tool", DEFAULT_OCAML_DEP_TOOL))
                .setYaccCompiler(getTool.apply("yacc.compiler", DEFAULT_OCAML_YACC_COMPILER))
                .setLexCompiler(getTool.apply("lex.compiler", DEFAULT_OCAML_LEX_COMPILER))
                .setOcamlInteropIncludesDir(
                    context.getBuckConfig().getValue(SECTION, "interop.includes"))
                .setWarningsFlags(context.getBuckConfig().getValue(SECTION, "warnings_flags"))
                .setOcamlBytecodeCompiler(
                    getTool.apply("ocaml.bytecode.compiler", DEFAULT_OCAML_BYTECODE_COMPILER))
                .setOcamlDebug(getTool.apply("debug", DEFAULT_OCAML_DEBUG))
                .setCCompiler(cxxPlatform.getCc())
                .setCxxCompiler(cxxPlatform.getCxx())
                .setCPreprocessor(cxxPlatform.getCpp())
                .setCFlags(
                    ImmutableList.<String>builder()
                        .addAll(cxxPlatform.getCppflags())
                        .addAll(cxxPlatform.getCflags())
                        .addAll(cxxPlatform.getAsflags())
                        .build())
                .setLdFlags(cxxPlatform.getLdflags())
                .setCxxPlatform(cxxPlatform)
                .build()));
  }
}
