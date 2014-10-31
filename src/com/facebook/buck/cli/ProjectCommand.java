/*
 * Copyright 2012-present Facebook, Inc.
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

import com.facebook.buck.apple.AppleBuildRules;
import com.facebook.buck.apple.XcodeProjectConfig;
import com.facebook.buck.apple.XcodeProjectConfigDescription;
import com.facebook.buck.apple.XcodeWorkspaceConfig;
import com.facebook.buck.apple.XcodeWorkspaceConfigDescription;
import com.facebook.buck.apple.xcode.ProjectGenerator;
import com.facebook.buck.apple.xcode.SeparatedProjectsGenerator;
import com.facebook.buck.apple.xcode.WorkspaceAndProjectGenerator;
import com.facebook.buck.command.Project;
import com.facebook.buck.java.JavaLibraryDescription;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.parser.AssociatedRulePredicate;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.parser.TargetNodePredicateSpec;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.ProjectConfig;
import com.facebook.buck.rules.ProjectConfigDescription;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TestRule;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessManager;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ProjectCommand extends AbstractCommandRunner<ProjectCommandOptions> {

  private static final Logger LOG = Logger.get(ProjectCommand.class);

  /**
   * Include java library targets (and android library targets) that use annotation
   * processing.  The sources generated by these annotation processors is needed by
   * IntelliJ.
   */
  private static final Predicate<TargetNode<?>> ANNOTATION_PREDICATE =
      new Predicate<TargetNode<?>>() {
        @Override
        public boolean apply(TargetNode<?> input) {
          Object constructorArg = input.getConstructorArg();
          if (!(constructorArg instanceof JavaLibraryDescription.Arg)) {
            return false;
          }
          JavaLibraryDescription.Arg arg = ((JavaLibraryDescription.Arg) constructorArg);
          return !arg.annotationProcessors.get().isEmpty();
        }
      };

  private static final String XCODE_PROCESS_NAME = "Xcode";

  private static class TargetGraphs {
    private final TargetGraph mainGraph;
    private final Optional<TargetGraph> testGraph;
    private final TargetGraph projectGraph;

    public TargetGraphs(
        TargetGraph mainGraph,
        Optional<TargetGraph> testGraph,
        TargetGraph projectGraph) {
      this.mainGraph = mainGraph;
      this.testGraph = testGraph;
      this.projectGraph = projectGraph;
    }

    public TargetGraph getMainGraph() {
      return mainGraph;
    }

    public Optional<TargetGraph> getTestGraph() {
      return testGraph;
    }

    public TargetGraph getProjectGraph() {
      return projectGraph;
    }
  }

  public ProjectCommand(CommandRunnerParams params) {
    super(params);
  }

  @Override
  ProjectCommandOptions createOptions(BuckConfig buckConfig) {
    return new ProjectCommandOptions(buckConfig);
  }

  @Override
  int runCommandWithOptionsInternal(ProjectCommandOptions options)
      throws IOException, InterruptedException {
    switch (options.getIde()) {
      case INTELLIJ:
        return runIntellijProjectGenerator(options);
      case XCODE:
        return runXcodeProjectGenerator(options);
      default:
        // unreachable
        throw new IllegalStateException("'ide' should always be of type 'INTELLIJ' or 'XCODE'");
    }
  }

  /**
   * Run intellij specific project generation actions.
   */
  int runIntellijProjectGenerator(ProjectCommandOptions options)
      throws IOException, InterruptedException {
    // Create an ActionGraph that only contains targets that can be represented as IDE
    // configuration files.
    ActionGraph actionGraph;

    try {
      actionGraph = createTargetGraphs(options).getProjectGraph().getActionGraph(getBuckEventBus());
    } catch (BuildTargetException | BuildFileParseException e) {
      throw new HumanReadableException(e);
    }

    ExecutionContext executionContext = createExecutionContext(
        options,
        actionGraph);

    Project project = new Project(
        new SourcePathResolver(new BuildRuleResolver(actionGraph.getNodes())),
        ImmutableSet.copyOf(
            FluentIterable
                .from(actionGraph.getNodes())
                .filter(
                    new Predicate<BuildRule>() {
                      @Override
                      public boolean apply(BuildRule input) {
                        return input instanceof ProjectConfig;
                      }
                    })
                .transform(
                    new Function<BuildRule, ProjectConfig>() {
                      @Override
                      public ProjectConfig apply(BuildRule input) {
                        return (ProjectConfig) input;
                      }
                    }
                )),
        actionGraph,
        options.getBasePathToAliasMap(),
        options.getJavaPackageFinder(),
        executionContext,
        getProjectFilesystem(),
        options.getPathToDefaultAndroidManifest(),
        options.getPathToPostProcessScript(),
        options.getBuckConfig().getPythonInterpreter(),
        getObjectMapper());

    File tempDir = Files.createTempDir();
    File tempFile = new File(tempDir, "project.json");
    int exitCode;
    try {
      exitCode = project.createIntellijProject(
          tempFile,
          executionContext.getProcessExecutor(),
          !options.getArgumentsFormattedAsBuildTargets().isEmpty(),
          console.getStdOut(),
          console.getStdErr());
      if (exitCode != 0) {
        return exitCode;
      }

      List<String> additionalInitialTargets = ImmutableList.of();
      if (options.shouldProcessAnnotations()) {
        try {
          additionalInitialTargets = getAnnotationProcessingTargets(options);
        } catch (BuildTargetException | BuildFileParseException e) {
          throw new HumanReadableException(e);
        }
      }

      // Build initial targets.
      if (options.hasInitialTargets() || !additionalInitialTargets.isEmpty()) {
        BuildCommand buildCommand = new BuildCommand(getCommandRunnerParams());
        BuildCommandOptions buildOptions =
            options.createBuildCommandOptionsWithInitialTargets(additionalInitialTargets);


        exitCode = buildCommand.runCommandWithOptions(buildOptions);
        if (exitCode != 0) {
          return exitCode;
        }
      }
    } finally {
      // Either leave project.json around for debugging or delete it on exit.
      if (console.getVerbosity().shouldPrintOutput()) {
        getStdErr().printf("project.json was written to %s", tempFile.getAbsolutePath());
      } else {
        tempFile.delete();
        tempDir.delete();
      }
    }

    if (options.getArguments().isEmpty()) {
      String greenStar = console.getAnsi().asHighlightedSuccessText(" * ");
      getStdErr().printf(
          console.getAnsi().asHighlightedSuccessText("=== Did you know ===") + "\n" +
              greenStar + "You can run `buck project <target>` to generate a minimal project " +
              "just for that target.\n" +
              greenStar + "This will make your IDE faster when working on large projects.\n" +
              greenStar + "See buck project --help for more info.\n" +
              console.getAnsi().asHighlightedSuccessText(
                  "--=* Knowing is half the battle!") + "\n");
    }

    return 0;
  }

  ImmutableList<String> getAnnotationProcessingTargets(ProjectCommandOptions options)
      throws BuildTargetException, BuildFileParseException, IOException, InterruptedException {
    ImmutableSet<BuildTarget> buildTargets = getRootsFromOptionsWithPredicate(
        options,
        ANNOTATION_PREDICATE);
    return FluentIterable
        .from(buildTargets)
        .transform(Functions.toStringFunction())
        .toList();
  }

  /**
   * Run xcode specific project generation actions.
   */
  int runXcodeProjectGenerator(ProjectCommandOptions options)
      throws IOException, InterruptedException {
    checkForAndKillXcodeIfRunning(options.getIdePrompt());

    TargetGraphs targetGraphs;
    SourcePathResolver resolver;
    try {
      targetGraphs = createTargetGraphs(options);
      resolver = new SourcePathResolver(
          new BuildRuleResolver(
              targetGraphs.getProjectGraph().getActionGraph(getBuckEventBus()).getNodes()));
    } catch (BuildTargetException | BuildFileParseException e) {
      throw new HumanReadableException(e);
    }

    ImmutableSet<BuildTarget> passedInTargetsSet;

    try {
      ImmutableSet<String> argumentsAsBuildTargets = options.getArgumentsFormattedAsBuildTargets();
      passedInTargetsSet = ImmutableSet.copyOf(getBuildTargets(argumentsAsBuildTargets));
    } catch (NoSuchBuildTargetException e) {
      throw new HumanReadableException(e);
    }

    ExecutionContext executionContext = createExecutionContext(
        options,
        targetGraphs.getProjectGraph().getActionGraph(getBuckEventBus()));

    ImmutableSet.Builder<ProjectGenerator.Option> optionsBuilder = ImmutableSet.builder();
    if (options.getReadOnly()) {
      optionsBuilder.add(ProjectGenerator.Option.GENERATE_READ_ONLY_FILES);
    }
    if (options.isWithTests()) {
      optionsBuilder.add(ProjectGenerator.Option.INCLUDE_TESTS);
    }

    if (options.getCombinedProject()) {
      // Generate a single project containing a target and all its dependencies and tests.
      ProjectGenerator projectGenerator = new ProjectGenerator(
          resolver,
          targetGraphs.getProjectGraph().getActionGraph(getBuckEventBus()).getNodes(),
          passedInTargetsSet,
          getProjectFilesystem(),
          executionContext,
          getProjectFilesystem().getPathForRelativePath(Paths.get("_gen")),
          "GeneratedProject",
          optionsBuilder.addAll(ProjectGenerator.COMBINED_PROJECT_OPTIONS).build());
      projectGenerator.createXcodeProjects();
    } else if (options.getWorkspaceAndProjects()) {
      ImmutableSet<BuildTarget> targets;
      if (passedInTargetsSet.isEmpty()) {
        targets = getAllTargetsOfType(
            targetGraphs.getMainGraph().getNodes(),
            XcodeWorkspaceConfigDescription.TYPE);
      } else {
        targets = passedInTargetsSet;
      }
      LOG.debug("Generating workspace for config targets %s", targets);
      Map<BuildRule, ProjectGenerator> projectGenerators = new HashMap<>();
      for (BuildTarget workspaceConfig : targets) {
        BuildRule workspaceRule =
            Preconditions.checkNotNull(
                targetGraphs.getMainGraph().getActionGraph(getBuckEventBus()).findBuildRuleByTarget(
                    workspaceConfig));
        if (!(workspaceRule instanceof XcodeWorkspaceConfig)) {
          throw new HumanReadableException(
              "%s must be a xcode_workspace_config",
              workspaceRule.getFullyQualifiedName());
        }
        Iterable<BuildRule> testBuildRules;
        if (targetGraphs.getTestGraph().isPresent()) {
          testBuildRules = targetGraphs
              .getTestGraph()
              .get()
              .getActionGraph(getBuckEventBus())
              .getNodes();
        } else {
          testBuildRules = Collections.emptySet();
        }
        XcodeWorkspaceConfig workspaceConfigRule = (XcodeWorkspaceConfig) workspaceRule;
        WorkspaceAndProjectGenerator generator = new WorkspaceAndProjectGenerator(
            resolver,
            getProjectFilesystem(),
            targetGraphs.getProjectGraph().getActionGraph(getBuckEventBus()),
            executionContext,
            workspaceConfigRule,
            optionsBuilder.build(),
            AppleBuildRules.getSourceRuleToTestRulesMap(testBuildRules),
            workspaceConfigRule.getExtraTests());
        generator.generateWorkspaceAndDependentProjects(projectGenerators);
      }
    } else {
      // Generate projects based on xcode_project_config rules, and place them in the same directory
      // as the Buck file.

      ImmutableSet<BuildTarget> targets;
      if (passedInTargetsSet.isEmpty()) {
        targets = getAllTargetsOfType(
            targetGraphs.getProjectGraph().getNodes(),
            XcodeProjectConfigDescription.TYPE);
      } else {
        targets = passedInTargetsSet;
      }

      SeparatedProjectsGenerator projectGenerator = new SeparatedProjectsGenerator(
          resolver,
          getProjectFilesystem(),
          targetGraphs.getProjectGraph().getActionGraph(getBuckEventBus()),
          executionContext,
          targets,
          optionsBuilder.build());
      projectGenerator.generateProjects();
    }

    return 0;
  }

  private void checkForAndKillXcodeIfRunning(boolean enablePrompt)
      throws InterruptedException, IOException {
    Optional<ProcessManager> processManager = getProcessManager();
    if (!processManager.isPresent()) {
      LOG.warn("Could not check if Xcode is running (no process manager)");
      return;
    }

    if (!processManager.get().isProcessRunning(XCODE_PROCESS_NAME)) {
      LOG.debug("Xcode is not running.");
      return;
    }

    if (enablePrompt && canPrompt()) {
      if (prompt(
              "Xcode is currently running. Buck will modify files Xcode currently has " +
              "open, which can cause it to become unstable.\n\n" +
              "Kill Xcode and continue?")) {
        processManager.get().killProcess(XCODE_PROCESS_NAME);
      } else {
        console.getStdOut().println(
            console.getAnsi().asWarningText(
                "Xcode is running. Generated projects might be lost or corrupted if Xcode " +
                "currently has them open."));
      }
      console.getStdOut().print(
          "To disable this prompt in the future, add the following to .buckconfig.local: \n\n" +
          "[project]\n" +
          "  ide_prompt = false\n\n");
    } else {
      LOG.debug(
          "Xcode is running, but cannot prompt to kill it (enabled %s, can prompt %s)",
          enablePrompt, canPrompt());
    }
  }

  private boolean canPrompt() {
    return System.console() != null;
  }

  private boolean prompt(String prompt) throws IOException {
    Preconditions.checkState(canPrompt());

    LOG.debug("Displaying prompt %s..", prompt);
    console.getStdOut().print(console.getAnsi().asWarningText(prompt + " [Y/n] "));

    Optional<String> result;
    try (InputStreamReader stdinReader = new InputStreamReader(System.in, Charsets.UTF_8);
         BufferedReader bufferedStdinReader = new BufferedReader(stdinReader)) {
      result = Optional.fromNullable(bufferedStdinReader.readLine());
    }
    LOG.debug("Result of prompt: [%s]", result);
    return result.isPresent() &&
      (result.get().isEmpty() || result.get().toLowerCase(Locale.US).startsWith("y"));
  }

  private static ImmutableSet<BuildTarget> getAllTargetsOfType(
      Iterable<TargetNode<?>> nodes,
      BuildRuleType type) {
    ImmutableSet.Builder<BuildTarget> targetsBuilder = ImmutableSet.builder();
    for (TargetNode<?> node : nodes) {
      if (node.getType() == type) {
        targetsBuilder.add(node.getBuildTarget());
      }
    }
    return targetsBuilder.build();
  }

  private ImmutableSet<BuildTarget> getRootsFromOptionsWithPredicate(
      ProjectCommandOptions options,
      Predicate<TargetNode<?>> rootsPredicate)
      throws BuildFileParseException, BuildTargetException, InterruptedException, IOException {
    ImmutableSet<String> argumentsAsBuildTargets = options.getArgumentsFormattedAsBuildTargets();
    if (!argumentsAsBuildTargets.isEmpty()) {
      return getBuildTargets(argumentsAsBuildTargets);
    }
    return getParser().filterAllTargetsInProject(
        getProjectFilesystem(),
        options.getDefaultIncludes(),
        rootsPredicate,
        console,
        environment,
        getBuckEventBus(),
        /* enableProfiling */ false);
  }

  private TargetGraphs createTargetGraphs(final ProjectCommandOptions options)
      throws BuildFileParseException, BuildTargetException, InterruptedException, IOException {
    Predicate<TargetNode<?>> projectRootsPredicate;
    Predicate<TargetNode<?>> projectPredicate;
    AssociatedRulePredicate associatedProjectPredicate;

    // Prepare the predicates to create the project graph based on the IDE.
    switch (options.getIde()) {
      case INTELLIJ:
        projectRootsPredicate = new Predicate<TargetNode<?>>() {
          @Override
          public boolean apply(TargetNode<?> input) {
            return input.getType() == ProjectConfigDescription.TYPE;
          }
        };
        projectPredicate = projectRootsPredicate;
        associatedProjectPredicate = new AssociatedRulePredicate() {
          @Override
          public boolean isMatch(BuildRule buildRule, ActionGraph actionGraph) {
            ProjectConfig projectConfig;
            if (buildRule instanceof ProjectConfig) {
              projectConfig = (ProjectConfig) buildRule;
            } else {
              return false;
            }

            BuildRule projectRule = projectConfig.getProjectRule();
            return (projectRule != null &&
                actionGraph.findBuildRuleByTarget(projectRule.getBuildTarget()) != null);
          }
        };
        break;
      case XCODE:
        final ImmutableSet<String> defaultExcludePaths = options.getDefaultExcludePaths();
        final ImmutableSet<BuildTarget> passedInTargetsSet =
            ImmutableSet.copyOf(getBuildTargets(options.getArgumentsFormattedAsBuildTargets()));

        projectRootsPredicate = new Predicate<TargetNode<?>>() {
          @Override
          public boolean apply(TargetNode<?> input) {
            BuildRuleType filterType = options.getWorkspaceAndProjects() ?
                XcodeWorkspaceConfigDescription.TYPE :
                XcodeProjectConfigDescription.TYPE;
            if (filterType != input.getType()) {
              return false;
            }

            String targetName = input.getBuildTarget().getFullyQualifiedName();
            for (String prefix : defaultExcludePaths) {
              if (targetName.startsWith("//" + prefix) &&
                  !passedInTargetsSet.contains(input.getBuildTarget())) {
                LOG.debug(
                    "Ignoring build target %s (exclude_paths contains %s)",
                    input.getBuildTarget(),
                    prefix);
                return false;
              }
            }
            return true;
          }
        };
        projectPredicate = new Predicate<TargetNode<?>>() {
          @Override
          public boolean apply(TargetNode<?> input) {
            return input.getType() == XcodeProjectConfigDescription.TYPE;
          }
        };
        associatedProjectPredicate = new AssociatedRulePredicate() {
          @Override
          public boolean isMatch(
              BuildRule buildRule, ActionGraph actionGraph) {
            XcodeProjectConfig xcodeProjectConfig;
            if (buildRule instanceof XcodeProjectConfig) {
              xcodeProjectConfig = (XcodeProjectConfig) buildRule;
            } else {
              return false;
            }

            for (BuildRule includedBuildRule : xcodeProjectConfig.getRules()) {
              if (actionGraph.findBuildRuleByTarget(includedBuildRule.getBuildTarget()) != null) {
                return true;
              }
            }

            return false;
          }
        };
        break;
      default:
        // unreachable
        throw new IllegalStateException("'ide' should always be of type 'INTELLIJ' or 'XCODE'");
    }

    TargetGraph fullGraph = getParser().buildTargetGraphForTargetNodeSpecs(
        ImmutableList.of(
            new TargetNodePredicateSpec(
                Predicates.<TargetNode<?>>alwaysTrue(),
                getProjectFilesystem().getIgnorePaths())),
        options.getDefaultIncludes(),
        getBuckEventBus(),
        console,
        environment,
        options.getEnableProfiling());

    // Create the main graph. This contains all the targets in the project slice, or all the valid
    // project roots if a project slice is not specified, and their transitive dependencies.
    ImmutableSet<BuildTarget> mainRoots = getRootsFromOptionsWithPredicate(
        options,
        projectRootsPredicate);
    TargetGraph mainGraph = getParser().buildTargetGraphForBuildTargets(
        mainRoots,
        options.getDefaultIncludes(),
        getBuckEventBus(),
        console,
        environment,
        options.getEnableProfiling());

    // Optionally create the test graph. This contains all the tests that cover targets in the main
    // graph, all the transitive dependencies of those tests, and all the targets in the main graph.
    Optional<TargetGraph> testGraph = Optional.absent();
    if (options.isWithTests()) {
      Predicate<TargetNode<?>> testPredicate = new Predicate<TargetNode<?>>() {
        @Override
        public boolean apply(TargetNode<?> input) {
          return input.getType().isTestRule();
        }
      };

      AssociatedRulePredicate associatedTestsPredicate = new AssociatedRulePredicate() {
        @Override
        public boolean isMatch(BuildRule buildRule, ActionGraph actionGraph) {
          TestRule testRule;
          if (buildRule instanceof TestRule) {
            testRule = (TestRule) buildRule;
          } else {
            return false;
          }
          for (BuildRule buildRuleUnderTest : testRule.getSourceUnderTest()) {
            if (actionGraph.findBuildRuleByTarget(buildRuleUnderTest.getBuildTarget()) != null) {
              return true;
            }
          }
          return false;
        }
      };

      testGraph = Optional.of(
          getAssociatedTargetGraph(
              mainGraph,
              mainRoots,
              fullGraph,
              testPredicate,
              associatedTestsPredicate,
              options));
    }

    // Create the project graph. This contains all the projects that reference the targets in the
    // main graph, or the test graph if present, and all the transitive dependencies of those
    // projects.
    TargetGraph projectGraph = getAssociatedTargetGraph(
        testGraph.or(mainGraph),
        /* additionalRoots */ ImmutableSet.<BuildTarget>of(),
        fullGraph,
        projectPredicate,
        associatedProjectPredicate,
        options);

    return new TargetGraphs(
        mainGraph,
        testGraph,
        projectGraph);
  }

  private static ImmutableSet<BuildTarget> filterTargetsFromGraph(
      TargetGraph graph,
      Predicate<TargetNode<?>> predicate) {
    return FluentIterable
        .from(graph.getNodes())
        .filter(predicate)
        .transform(HasBuildTarget.TO_TARGET)
        .toSet();
  }

  /**
   * @param targetGraph The TargetGraph the nodes of the new TargetGraph are related to.
   * @param additionalRoots Additional roots to be used to create the new TargetGraph.
   * @param fullGraph A TargetGraph containing all nodes that could be related.
   * @param predicate A predicate that all related nodes pass. Unrelated nodes can pass it as well.
   * @param associatedRulePredicate A predicate to determine whether a node is related or not.
   * @return A TargetGraph with nodes related to the given {@code targetGraph}.
   */
  private TargetGraph getAssociatedTargetGraph(
      TargetGraph targetGraph,
      ImmutableSet<BuildTarget> additionalRoots,
      TargetGraph fullGraph,
      Predicate<TargetNode<?>> predicate,
      AssociatedRulePredicate associatedRulePredicate,
      ProjectCommandOptions options)
      throws BuildFileParseException, BuildTargetException, InterruptedException, IOException {
    ImmutableSet<BuildTarget> candidateTargets = filterTargetsFromGraph(fullGraph, predicate);
    TargetGraph candidateGraph = getParser().buildTargetGraphForBuildTargets(
        candidateTargets,
        options.getDefaultIncludes(),
        getBuckEventBus(),
        console,
        environment,
        options.getEnableProfiling());

    ImmutableSet.Builder<BuildTarget> rootsBuilder = ImmutableSet.builder();
    rootsBuilder.addAll(additionalRoots);

    ActionGraph actionGraph = targetGraph.getActionGraph(getBuckEventBus());
    ActionGraph candidateActionGraph = candidateGraph.getActionGraph(getBuckEventBus());

    for (BuildTarget buildTarget : candidateTargets) {
      BuildRule buildRule = candidateActionGraph.findBuildRuleByTarget(buildTarget);
      if (buildRule != null && associatedRulePredicate.isMatch(buildRule, actionGraph)) {
        rootsBuilder.add(buildRule.getBuildTarget());
      }
    }

    return getParser().buildTargetGraphForBuildTargets(
        rootsBuilder.build(),
        options.getDefaultIncludes(),
        getBuckEventBus(),
        console,
        environment,
        options.getEnableProfiling());
  }

  @Override
  String getUsageIntro() {
    return "generates project configuration files for an IDE";
  }
}
