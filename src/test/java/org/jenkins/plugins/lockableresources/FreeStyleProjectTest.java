package org.jenkins.plugins.lockableresources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.User;
import hudson.model.queue.QueueTaskFuture;
import hudson.triggers.TimerTrigger;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import jenkins.model.Jenkins;
import org.jenkins.plugins.lockableresources.actions.LockableResourcesRootAction;
import org.jenkins.plugins.lockableresources.queue.LockableResourcesQueueTaskDispatcher;
import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.SecureGroovyScript;
import org.jenkinsci.plugins.scriptsecurity.scripts.ApprovalContext;
import org.jenkinsci.plugins.scriptsecurity.scripts.ScriptApproval;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.MockBuilder;
import org.jvnet.hudson.test.SleepBuilder;
import org.jvnet.hudson.test.TestExtension;

public class FreeStyleProjectTest {

  @Rule public JenkinsRule j = new JenkinsRule();

  @Test
  @Issue("JENKINS-34853")
  public void security170fix() throws Exception {
    LockableResourcesManager.get().createResource("resource1");
    FreeStyleProject p = j.createFreeStyleProject("p");
    p.addProperty(new RequiredResourcesProperty("resource1", "resourceNameVar", null, null, null));
    p.getBuildersList().add(new PrinterBuilder());

    FreeStyleBuild b1 = p.scheduleBuild2(0).get();
    j.assertLogContains("resourceNameVar: resource1", b1);
    j.assertBuildStatus(Result.SUCCESS, b1);
  }

  @Test
  public void migrateToScript() throws Exception {
    LockableResourcesManager.get().createResource("resource1");

    FreeStyleProject p = j.createFreeStyleProject("p");
    p.addProperty(
        new RequiredResourcesProperty(
            null, null, null, "groovy:resourceName == 'resource1'", null));

    p.save();

    j.jenkins.reload();

    FreeStyleProject p2 = j.jenkins.getItemByFullName("p", FreeStyleProject.class);
    RequiredResourcesProperty newProp = p2.getProperty(RequiredResourcesProperty.class);
    assertNull(newProp.getLabelName());
    assertNotNull(newProp.getResourceMatchScript());
    assertEquals("resourceName == 'resource1'", newProp.getResourceMatchScript().getScript());

    p2.getBuildersList().add(new SleepBuilder(5000));

    FreeStyleProject p3 = j.createFreeStyleProject("p3");
    p3.addProperty(new RequiredResourcesProperty("resource1", null, "1", null, null));
    p3.getBuildersList().add(new SleepBuilder(10000));

    final QueueTaskFuture<FreeStyleBuild> taskA =
        p3.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause());
    Thread.sleep(2500);
    final QueueTaskFuture<FreeStyleBuild> taskB =
        p2.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause());

    final FreeStyleBuild buildA = taskA.get(60, TimeUnit.SECONDS);
    final FreeStyleBuild buildB = taskB.get(60, TimeUnit.SECONDS);

    long buildAEndTime = buildA.getStartTimeInMillis() + buildA.getDuration();
    assertTrue(
        "Project A build should be finished before the build of project B starts. "
            + "A finished at "
            + buildAEndTime
            + ", B started at "
            + buildB.getStartTimeInMillis(),
        buildB.getStartTimeInMillis() >= buildAEndTime);
  }

  @Test
  public void configRoundTrip() throws Exception {
    LockableResourcesManager.get().createResource("resource1");

    FreeStyleProject withResource = j.createFreeStyleProject("withResource");
    withResource.addProperty(
        new RequiredResourcesProperty("resource1", "resourceNameVar", null, null, null));
    FreeStyleProject withResourceRoundTrip = j.configRoundtrip(withResource);

    RequiredResourcesProperty withResourceProp =
        withResourceRoundTrip.getProperty(RequiredResourcesProperty.class);
    assertNotNull(withResourceProp);
    assertEquals("resource1", withResourceProp.getResourceNames());
    assertEquals("resourceNameVar", withResourceProp.getResourceNamesVar());
    assertNull(withResourceProp.getResourceNumber());
    assertNull(withResourceProp.getLabelName());
    assertNull(withResourceProp.getResourceMatchScript());

    FreeStyleProject withLabel = j.createFreeStyleProject("withLabel");
    withLabel.addProperty(new RequiredResourcesProperty(null, null, null, "some-label", null));
    FreeStyleProject withLabelRoundTrip = j.configRoundtrip(withLabel);

    RequiredResourcesProperty withLabelProp =
        withLabelRoundTrip.getProperty(RequiredResourcesProperty.class);
    assertNotNull(withLabelProp);
    assertNull(withLabelProp.getResourceNames());
    assertNull(withLabelProp.getResourceNamesVar());
    assertNull(withLabelProp.getResourceNumber());
    assertEquals("some-label", withLabelProp.getLabelName());
    assertNull(withLabelProp.getResourceMatchScript());

    FreeStyleProject withScript = j.createFreeStyleProject("withScript");
    SecureGroovyScript origScript = new SecureGroovyScript("return true", false, null);
    withScript.addProperty(new RequiredResourcesProperty(null, null, null, null, origScript));
    FreeStyleProject withScriptRoundTrip = j.configRoundtrip(withScript);

    RequiredResourcesProperty withScriptProp =
        withScriptRoundTrip.getProperty(RequiredResourcesProperty.class);
    assertNotNull(withScriptProp);
    assertNull(withScriptProp.getResourceNames());
    assertNull(withScriptProp.getResourceNamesVar());
    assertNull(withScriptProp.getResourceNumber());
    assertNull(withScriptProp.getLabelName());
    assertNotNull(withScriptProp.getResourceMatchScript());
    assertEquals("return true", withScriptProp.getResourceMatchScript().getScript());
    assertFalse(withScriptProp.getResourceMatchScript().isSandbox());
  }

  @Test
  public void approvalRequired() throws Exception {
    LockableResourcesManager.get().createResource(LockableResourcesRootAction.ICON);

    j.jenkins.setSecurityRealm(j.createDummySecurityRealm());

    j.jenkins.setAuthorizationStrategy(
        new MockAuthorizationStrategy()
            .grant(Jenkins.READ, Item.READ)
            .everywhere()
            .toAuthenticated()
            .grant(Jenkins.ADMINISTER)
            .everywhere()
            .to("bob")
            .grant(Item.CONFIGURE, Item.BUILD)
            .everywhere()
            .to("alice"));

    final String SCRIPT =
        "resourceName == org.jenkins.plugins.lockableresources.actions.LockableResourcesRootAction.ICON;";

    FreeStyleProject p = j.createFreeStyleProject();
    SecureGroovyScript groovyScript =
        new SecureGroovyScript(SCRIPT, true, null).configuring(ApprovalContext.create());

    p.addProperty(new RequiredResourcesProperty(null, null, null, null, groovyScript));

    User.getOrCreateByIdOrFullName("alice");
    JenkinsRule.WebClient wc = j.createWebClient();
    wc.login("alice");

    QueueTaskFuture<FreeStyleBuild> futureBuild = p.scheduleBuild2(0);

    // Sleeping briefly to make sure the queue gets updated.
    Thread.sleep(2000);

    List<Queue.Item> items = j.jenkins.getQueue().getItems(p);
    assertNotNull(items);
    assertEquals(1, items.size());

    assertTrue(items.get(0) instanceof Queue.BlockedItem);

    Queue.BlockedItem blockedItem = (Queue.BlockedItem) items.get(0);
    assertTrue(
        blockedItem.getCauseOfBlockage()
            instanceof LockableResourcesQueueTaskDispatcher.BecauseResourcesQueueFailed);

    ScriptApproval approval = ScriptApproval.get();
    List<ScriptApproval.PendingSignature> pending = new ArrayList<>();
    pending.addAll(approval.getPendingSignatures());

    assertFalse(pending.isEmpty());
    assertEquals(1, pending.size());
    ScriptApproval.PendingSignature firstPending = pending.get(0);

    assertEquals(
        "staticField org.jenkins.plugins.lockableresources.actions.LockableResourcesRootAction ICON",
        firstPending.signature);
    approval.approveSignature(firstPending.signature);

    j.assertBuildStatusSuccess(futureBuild);
  }

  @Test
  public void autoCreateResource() throws IOException, InterruptedException, ExecutionException {
    FreeStyleProject f = j.createFreeStyleProject("f");
    f.addProperty(new RequiredResourcesProperty("resource1", null, null, null, null));

    FreeStyleBuild fb1 = f.scheduleBuild2(0).waitForStart();
    j.waitForMessage("acquired lock on [resource1]", fb1);
    j.waitForCompletion(fb1);

    assertNull(LockableResourcesManager.get().fromName("resource1"));
  }

  @TestExtension
  public static class PrinterBuilder extends MockBuilder {

    public PrinterBuilder() {
      super(Result.SUCCESS);
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
        throws InterruptedException, IOException {
      listener
          .getLogger()
          .println("resourceNameVar: " + build.getEnvironment(listener).get("resourceNameVar"));
      return true;
    }
  }
}
