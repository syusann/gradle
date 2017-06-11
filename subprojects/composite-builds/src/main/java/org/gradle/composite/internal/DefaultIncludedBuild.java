/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.composite.internal;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import org.gradle.api.Action;
import org.gradle.api.Task;
import org.gradle.api.artifacts.DependencySubstitutions;
import org.gradle.api.internal.GradleInternal;
import org.gradle.api.internal.SettingsInternal;
import org.gradle.api.internal.artifacts.ImmutableModuleIdentifierFactory;
import org.gradle.api.internal.artifacts.ivyservice.dependencysubstitution.DefaultDependencySubstitutions;
import org.gradle.api.internal.artifacts.ivyservice.dependencysubstitution.DependencySubstitutionsInternal;
import org.gradle.api.tasks.TaskReference;
import org.gradle.execution.TaskPathProjectEvaluator;
import org.gradle.execution.TaskSelector;
import org.gradle.initialization.DefaultBuildCancellationToken;
import org.gradle.initialization.GradleLauncher;
import org.gradle.internal.Factory;

import java.io.File;
import java.util.List;
import java.util.Set;

public class DefaultIncludedBuild implements IncludedBuildInternal {
    private final File projectDir;
    private final Factory<GradleLauncher> gradleLauncherFactory;
    private final ImmutableModuleIdentifierFactory moduleIdentifierFactory;
    private final List<Action<? super DependencySubstitutions>> dependencySubstitutionActions = Lists.newArrayList();

    private DefaultDependencySubstitutions dependencySubstitutions;

    private GradleLauncher gradleLauncher;
    private SettingsInternal settings;
    private GradleInternal gradle;
    private String name;

    public DefaultIncludedBuild(File projectDir, Factory<GradleLauncher> launcherFactory, ImmutableModuleIdentifierFactory moduleIdentifierFactory) {
        this.projectDir = projectDir;
        this.gradleLauncherFactory = launcherFactory;
        this.moduleIdentifierFactory = moduleIdentifierFactory;
    }

    public File getProjectDir() {
        return projectDir;
    }

    @Override
    public TaskReference task(String path) {
        Preconditions.checkArgument(path.startsWith(":"), "Task path '%s' is not a qualified task path (e.g. ':task' or ':project:task').", path);
        return new IncludedBuildTaskReference(getName(), path);
    }

    @Override
    public String getName() {
        if (name == null) {
            name = getLoadedSettings().getRootProject().getName();
        }
        return name;
    }

    @Override
    public void dependencySubstitution(Action<? super DependencySubstitutions> action) {
        if (dependencySubstitutions != null) {
            throw new IllegalStateException("Cannot configure included build after dependency substitutions are resolved.");
        }
        dependencySubstitutionActions.add(action);
    }

    public DependencySubstitutionsInternal resolveDependencySubstitutions() {
        if (dependencySubstitutions == null) {
            dependencySubstitutions = DefaultDependencySubstitutions.forIncludedBuild(this, moduleIdentifierFactory);

            for (Action<? super DependencySubstitutions> action : dependencySubstitutionActions) {
                action.execute(dependencySubstitutions);
            }
        }
        return dependencySubstitutions;
    }

    @Override
    public SettingsInternal getLoadedSettings() {
        if (settings == null) {
            GradleLauncher gradleLauncher = getGradleLauncher();
            settings = gradleLauncher.getLoadedSettings();
        }
        return settings;
    }

    @Override
    public GradleInternal getConfiguredBuild() {
        if (gradle == null) {
            GradleLauncher gradleLauncher = getGradleLauncher();
            settings = gradleLauncher.getLoadedSettings();
            gradle = gradleLauncher.getConfiguredBuild();
        }
        return gradle;
    }

    @Override
    public void addTasks(Iterable<String> taskPaths) {
        GradleInternal configuredBuild = getConfiguredBuild();
        TaskSelector taskSelector = new TaskSelector(configuredBuild, new TaskPathProjectEvaluator(new DefaultBuildCancellationToken()));
        for (String taskPath : taskPaths) {
            Set<Task> tasks = taskSelector.getSelection(taskPath).getTasks();
            configuredBuild.getTaskGraph().addTasks(tasks);
        }
    }

    @Override
    public void populateTaskGraph() {
        getConfiguredBuild().getTaskGraph().populate();
    }

    private GradleLauncher getGradleLauncher() {
        if (gradleLauncher == null) {
            gradleLauncher = gradleLauncherFactory.create();
            reset();
        }
        return gradleLauncher;
    }

    private void reset() {
        gradle = null;
        settings = null;
    }

    @Override
    public void execute(final Iterable<String> tasks, final Object listener) {
        final GradleLauncher launcher = getGradleLauncher();
        final GradleInternal gradle = launcher.getGradle();
        gradle.getStartParameter().setTaskNames(tasks);
        gradle.addListener(listener);
        try {
            launcher.runTasks(tasks);
        } finally {
            markAsNotReusable();
        }
    }

    private void markAsNotReusable() {
        gradleLauncher = null;
    }

    @Override
    public String toString() {
        return String.format("includedBuild[%s]", projectDir.getName());
    }
}
