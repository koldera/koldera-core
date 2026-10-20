package com.koldera.pdf.pipeline;

import com.koldera.pdf.cli.EngineVersion;
import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResult.Action;
import com.koldera.pdf.contracts.WorkerResult.ActionOutcome;
import com.koldera.pdf.contracts.WorkerResult.Artifact;
import com.koldera.pdf.contracts.WorkerResult.ArtifactKind;
import com.koldera.pdf.contracts.WorkerResult.ErrorCode;
import com.koldera.pdf.contracts.WorkerResult.Finding;
import com.koldera.pdf.contracts.WorkerResult.RemediationStatus;
import com.koldera.pdf.contracts.WorkerResult.Status;
import com.koldera.pdf.contracts.WorkerResult.Validation;
import com.koldera.pdf.contracts.WorkerResult.WorkerError;
import com.koldera.pdf.preflight.PdfPreflight;
import com.koldera.pdf.preflight.PreflightOutcome;
import com.koldera.pdf.validation.ValidationOutcome;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Accumulates evidence during a run and builds the contract result; no processing logic here. */
final class ResultAssembler {

  private final WorkerRequest request;
  private final JobWorkspace workspace;
  private final PreflightOutcome preflight;
  private Validation baseline = Validation.notRun();
  private Validation finalValidation;
  private final List<Action> actions = new ArrayList<>();
  private List<Finding> findings = List.of();
  private String remediatedName;

  ResultAssembler(WorkerRequest request, JobWorkspace workspace, PreflightOutcome preflight) {
    this.request = request;
    this.workspace = workspace;
    this.preflight = preflight;
  }

  void baseline(ValidationOutcome outcome, String reportName) {
    this.baseline = outcome.toContract(reportPathIfPresent(reportName));
  }

  void finalValidation(ValidationOutcome outcome, String reportName) {
    this.finalValidation = outcome.toContract(reportPathIfPresent(reportName));
  }

  /**
   * The final validation fails only the missing identifier, which was withheld pending human review: reported as
   * {@code declaration_withheld}, never as passed. The failed rule stays listed.
   */
  void declarationWithheld() {
    finalValidation = new Validation(finalValidation.profile(), WorkerResult.ValidationStatus.DECLARATION_WITHHELD, finalValidation.failedRules(), finalValidation.rawReportPath());
  }

  void actions(List<Action> applied) {
    actions.clear();
    actions.addAll(applied);
  }

  void findings(List<Finding> all) {
    this.findings = List.copyOf(all);
  }

  void published(String name) {
    this.remediatedName = name;
  }

  /** Marks every applied action as rolled back; skipped/failed actions keep their outcome. */
  void rollBackAppliedActions() {
    for (int i = 0; i < actions.size(); i++) {
      Action a = actions.get(i);
      if (a.outcome() == ActionOutcome.APPLIED) {
        actions.set(i, new Action(a.ruleId(), ActionOutcome.ROLLED_BACK, "rolled back: " + a.reason(), a.before(), a.after()));
      }
    }
  }

  WorkerResult build(Status status, RemediationStatus remediation, ErrorCode code, String message) {
    WorkerError error = code == null ? null : new WorkerError(code, message);
    return new WorkerResult(
        WorkerResult.SPEC_VERSION,
        request.jobId(),
        status,
        preflight.sha256(),
        EngineVersion.describe(),
        baseline,
        finalValidation,
        remediation,
        actions,
        findings,
        artifacts(),
        true,
        error);
  }

  RemediationStatus remediationWhenNotStarted() {
    return request.isMutating() ? RemediationStatus.SKIPPED : RemediationStatus.NOT_REQUESTED;
  }

  private List<Artifact> artifacts() {
    List<Artifact> list = new ArrayList<>();
    if (preflight.sha256() != null && preflight.bytes() > 0) {
      list.add(new Artifact(ArtifactKind.ORIGINAL, JobWorkspace.CANONICAL_INPUT, preflight.sha256(), preflight.bytes()));
    }
    addIfPresent(list, ArtifactKind.BASELINE_REPORT, PdfProcessor.BASELINE_REPORT);
    if (remediatedName != null) {
      addIfPresent(list, ArtifactKind.REMEDIATED, remediatedName);
    }
    addIfPresent(list, ArtifactKind.FINAL_REPORT, PdfProcessor.FINAL_REPORT);
    return list;
  }

  private void addIfPresent(List<Artifact> list, ArtifactKind kind, String name) {
    Path file = workspace.outputFile(name);
    try {
      if (Files.isRegularFile(file) && Files.size(file) > 0) {
        byte[] bytes = Files.readAllBytes(file);
        list.add(new Artifact(kind, workspace.canonicalOutputPath(name), PdfPreflight.sha256Hex(bytes), bytes.length));
      }
    } catch (IOException e) {
      // An unreadable artifact is simply not advertised; the result stays contract-valid.
    }
  }

  private String reportPathIfPresent(String name) {
    Path file = workspace.outputFile(name);
    return Files.isRegularFile(file) ? workspace.canonicalOutputPath(name) : null;
  }
}
