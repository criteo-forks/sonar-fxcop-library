/*
 * SonarQube FxCop Library
 * Copyright (C) 2014 SonarSource
 * dev@sonar.codehaus.org
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonar.plugins.fxcop;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.Sensor;
import org.sonar.api.batch.SensorContext;
import org.sonar.api.component.ResourcePerspectives;
import org.sonar.api.config.Settings;
import org.sonar.api.issue.Issuable;
import org.sonar.api.profiles.RulesProfile;
import org.sonar.api.resources.Project;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.rules.ActiveRule;
import org.sonar.api.scan.filesystem.FileQuery;
import org.sonar.api.scan.filesystem.ModuleFileSystem;

import java.io.File;
import java.util.List;

public class FxCopSensor implements Sensor {

  private static final Logger LOG = LoggerFactory.getLogger(FxCopSensor.class);

  private final String languageKey;
  private final String repositoryKey;
  private final String assemblyPropertyKey;
  private final String fxCopCmdPropertyKey;
  private final Settings settings;
  private final RulesProfile profile;
  private final ModuleFileSystem fileSystem;
  private final ResourcePerspectives perspectives;

  public FxCopSensor(String languageKey, String repositoryKey, String assemblyPropertyKey, String fxCopCmdPropertyKey,
    Settings settings, RulesProfile profile, ModuleFileSystem fileSystem, ResourcePerspectives perspectives) {

    this.languageKey = languageKey;
    this.repositoryKey = repositoryKey;
    this.assemblyPropertyKey = assemblyPropertyKey;
    this.fxCopCmdPropertyKey = fxCopCmdPropertyKey;
    this.settings = settings;
    this.profile = profile;
    this.fileSystem = fileSystem;
    this.perspectives = perspectives;
  }

  @Override
  public boolean shouldExecuteOnProject(Project project) {
    boolean shouldExecute;

    if (!settings.hasKey(assemblyPropertyKey) || !hasFilesToAnalyze()) {
      shouldExecute = false;
    } else if (profile.getActiveRulesByRepository(repositoryKey).isEmpty()) {
      LOG.info("All FxCop rules are disabled, skipping its execution.");
      shouldExecute = false;
    } else {
      shouldExecute = true;
    }

    return shouldExecute;
  }

  private boolean hasFilesToAnalyze() {
    return !fileSystem.files(FileQuery.onSource().onLanguage(languageKey)).isEmpty();
  }

  @Override
  public void analyse(Project project, SensorContext context) {
    analyse(context, new FileProvider(project), new FxCopExecutor());
  }

  @VisibleForTesting
  void analyse(SensorContext context, FileProvider fileProvider, FxCopExecutor executor) {
    File rulesetFile = new File(fileSystem.workingDir(), "fxcop-sonarqube.ruleset");
    new FxCopRulesetWriter().write(enabledRuleKeys(), rulesetFile);

    File reportFile = new File(fileSystem.workingDir(), "fxcop-report.xml");

    executor.execute(settings.getString(fxCopCmdPropertyKey), settings.getString(assemblyPropertyKey), rulesetFile, reportFile);

    for (FxCopIssue issue : new FxCopReportParser().parse(reportFile)) {
      if (!hasFileAndLine(issue)) {
        logSkippedIssue(issue, "which has no associated file.");
        continue;
      }

      File file = new File(new File(issue.path()), issue.file());
      org.sonar.api.resources.File sonarFile = fileProvider.fromIOFile(file);
      if (sonarFile == null) {
        logSkippedIssueOutsideOfSonarQube(issue, file);
      } else if (languageKey.equals(sonarFile.getLanguage().getKey())) {
        Issuable issuable = perspectives.as(Issuable.class, sonarFile);
        if (issuable == null) {
          logSkippedIssueOutsideOfSonarQube(issue, file);
        } else {
          issuable.addIssue(
            issuable.newIssueBuilder()
              .ruleKey(RuleKey.of(repositoryKey, issue.ruleKey()))
              .line(issue.line())
              .message(issue.message())
              .build());
        }
      }
    }
  }

  private static boolean hasFileAndLine(FxCopIssue issue) {
    return issue.path() != null && issue.file() != null && issue.line() != null;
  }

  private static void logSkippedIssueOutsideOfSonarQube(FxCopIssue issue, File file) {
    logSkippedIssue(issue, "whose file \"" + file.getAbsolutePath() + "\" is not in SonarQube.");
  }

  private static void logSkippedIssue(FxCopIssue issue, String reason) {
    LOG.info("Skipping the FxCop issue at line " + issue.reportLine() + " " + reason);
  }

  private List<String> enabledRuleKeys() {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    for (ActiveRule activeRule : profile.getActiveRulesByRepository(repositoryKey)) {
      builder.add(activeRule.getRuleKey());
    }
    return builder.build();
  }

}
