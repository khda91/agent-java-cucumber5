/*
 * Copyright 2020
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.khda91.reportportal.cucumber;

import com.epam.reportportal.listeners.ListenerParameters;
import com.epam.reportportal.service.Launch;
import com.epam.reportportal.service.ReportPortal;
import com.epam.ta.reportportal.ws.model.FinishExecutionRQ;
import com.epam.ta.reportportal.ws.model.StartTestItemRQ;
import com.epam.ta.reportportal.ws.model.launch.StartLaunchRQ;
import com.epam.ta.reportportal.ws.model.log.SaveLogRQ.File;
import io.cucumber.plugin.ConcurrentEventListener;
import io.cucumber.plugin.event.EmbedEvent;
import io.cucumber.plugin.event.EventHandler;
import io.cucumber.plugin.event.EventPublisher;
import io.cucumber.plugin.event.HookTestStep;
import io.cucumber.plugin.event.HookType;
import io.cucumber.plugin.event.Result;
import io.cucumber.plugin.event.TestCase;
import io.cucumber.plugin.event.TestCaseFinished;
import io.cucumber.plugin.event.TestCaseStarted;
import io.cucumber.plugin.event.TestRunFinished;
import io.cucumber.plugin.event.TestRunStarted;
import io.cucumber.plugin.event.TestSourceRead;
import io.cucumber.plugin.event.TestStep;
import io.cucumber.plugin.event.TestStepFinished;
import io.cucumber.plugin.event.TestStepStarted;
import io.cucumber.plugin.event.WriteEvent;
import io.reactivex.Maybe;
import org.apache.tika.mime.MimeTypeException;
import org.apache.tika.mime.MimeTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rp.com.google.common.base.Supplier;
import rp.com.google.common.base.Suppliers;

import java.util.Calendar;
import java.util.Date;

/**
 * Abstract Cucumber 5.x formatter for Report Portal
 */
public abstract class AbstractReporter implements ConcurrentEventListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractReporter.class);

    protected static final String COLON_INFIX = ": ";

    /* feature context */
    protected RunningContext.FeatureContext currentFeatureContext;

    /* scenario context */
    protected RunningContext.ScenarioContext currentScenarioContext;

    protected Supplier<Launch> rp;

    /**
     * Registers an event handler for a specific event.
     * <p>
     * The available events types are:
     * <ul>
     * <li>{@link TestRunStarted} - the first event sent.
     * <li>{@link TestSourceRead} - sent for each feature file read, contains the feature file source.
     * <li>{@link TestCaseStarted} - sent before starting the execution of a Test Case(/Pickle/Scenario), contains the Test Case
     * <li>{@link TestStepStarted} - sent before starting the execution of a Test Step, contains the Test Step
     * <li>{@link TestStepFinished} - sent after the execution of a Test Step, contains the Test Step and its Result.
     * <li>{@link TestCaseFinished} - sent after the execution of a Test Case(/Pickle/Scenario), contains the Test Case and its Result.
     * <li>{@link TestRunFinished} - the last event sent.
     * <li>{@link EmbedEvent} - calling scenario.embed in a hook triggers this event.
     * <li>{@link WriteEvent} - calling scenario.write in a hook triggers this event.
     * </ul>
     */
    @Override
    public void setEventPublisher(EventPublisher publisher) {
        publisher.registerHandlerFor(TestRunStarted.class, getTestRunStartedHandler());
        publisher.registerHandlerFor(TestSourceRead.class, getTestSourceReadHandler());
        publisher.registerHandlerFor(TestCaseStarted.class, getTestCaseStartedHandler());
        publisher.registerHandlerFor(TestStepStarted.class, getTestStepStartedHandler());
        publisher.registerHandlerFor(TestStepFinished.class, getTestStepFinishedHandler());
        publisher.registerHandlerFor(TestCaseFinished.class, getTestCaseFinishedHandler());
        publisher.registerHandlerFor(TestRunFinished.class, getTestRunFinishedHandler());
        publisher.registerHandlerFor(EmbedEvent.class, getEmbedEventHandler());
        publisher.registerHandlerFor(WriteEvent.class, getWriteEventHandler());
    }

    /**
     * Manipulations before the launch starts
     */
    protected void beforeLaunch() {
        startLaunch();
    }

    /**
     * Finish RP launch
     */
    protected void afterLaunch() {
        FinishExecutionRQ finishLaunchRq = new FinishExecutionRQ();
        finishLaunchRq.setEndTime(Calendar.getInstance().getTime());
        rp.get().finish(finishLaunchRq);
    }

    /**
     * Manipulations before the feature starts
     */
    protected void beforeFeature() {
        startFeature();
    }

    /**
     * Finish Cucumber feature
     */
    protected void afterFeature() {
        Utils.finishTestItem(rp.get(), currentFeatureContext.getFeatureId());
        currentFeatureContext = null;
    }

    /**
     * Start Cucumber scenario
     */
    protected void beforeScenario() {
        Maybe<String> id = Utils.startNonLeafNode(rp.get(),
                currentFeatureContext.getFeatureId(),
                Utils.buildNodeName(currentScenarioContext.getKeyword(), AbstractReporter.COLON_INFIX, currentScenarioContext.getName(), currentScenarioContext.getOutlineIteration()),
                currentFeatureContext.getUri() + ":" + currentScenarioContext.getLine(),
                currentScenarioContext.getTags(),
                getScenarioTestItemType()
        );
        currentScenarioContext.setId(id);
    }

    /**
     * Finish Cucumber scenario
     */
    protected void afterScenario(TestCaseFinished event) {
        Utils.finishTestItem(rp.get(), currentScenarioContext.getId(), event.getResult().getStatus().toString());
        currentScenarioContext = null;
    }

    /**
     * Start Cucumber feature
     */
    protected void startFeature() {
        StartTestItemRQ rq = new StartTestItemRQ();
        Maybe<String> root = getRootItemId();
        rq.setDescription(currentFeatureContext.getUri());
        rq.setName(Utils.buildNodeName(currentFeatureContext.getFeature().getKeyword(), AbstractReporter.COLON_INFIX, currentFeatureContext.getFeature().getName(), null));
        rq.setTags(currentFeatureContext.getTags());
        rq.setStartTime(Calendar.getInstance().getTime());
        rq.setType(getFeatureTestItemType());
        if (null == root) {
            currentFeatureContext.setFeatureId(rp.get().startTestItem(rq));
        } else {
            currentFeatureContext.setFeatureId(rp.get().startTestItem(root, rq));
        }
    }

    /**
     * Start RP launch
     */
    protected void startLaunch() {
        rp = Suppliers.memoize(new Supplier<Launch>() {

            /* should no be lazy */
            private final Date startTime = Calendar.getInstance().getTime();

            @Override
            public Launch get() {
                final ReportPortal reportPortal = ReportPortal.builder().build();
                ListenerParameters parameters = reportPortal.getParameters();

                StartLaunchRQ rq = new StartLaunchRQ();
                rq.setName(parameters.getLaunchName());
                rq.setStartTime(startTime);
                rq.setMode(parameters.getLaunchRunningMode());
                rq.setTags(parameters.getTags());
                rq.setDescription(parameters.getDescription());

                return reportPortal.newLaunch(rq);
            }
        });
    }

    /**
     * Start Cucumber step
     *
     * @param step Step object
     */
    protected abstract void beforeStep(TestStep step);

    /**
     * Finish Cucumber step
     *
     * @param result Step result
     */
    protected abstract void afterStep(Result result);

    /**
     * Called when before/after-hooks are started
     */

    protected abstract void beforeHooks(HookType hookType);

    /**
     * Called when before/after-hooks are finished
     *
     * @param isBefore - if true, before-hook is finished, if false - after-hook
     */
    protected abstract void afterHooks(Boolean isBefore);

    /**
     * Called when a specific before/after-hook is finished
     *
     * @param step     TestStep object
     * @param result   Hook result
     * @param isBefore - if true, before-hook, if false - after-hook
     */
    protected abstract void hookFinished(TestStep step, Result result, boolean isBefore);

    /**
     * Return RP test item name mapped to Cucumber feature
     *
     * @return test item name
     */
    protected abstract String getFeatureTestItemType();

    /**
     * Return RP test item name mapped to Cucumber scenario
     *
     * @return test item name
     */
    protected abstract String getScenarioTestItemType();

    /**
     * Report test item result and error (if present)
     *
     * @param result  - Cucumber result object
     * @param message - optional message to be logged in addition
     */
    protected void reportResult(Result result, String message) {
        String cukesStatus = result.getStatus().toString();
        String level = Utils.mapLevel(cukesStatus);
        Throwable error = result.getError();
        if (error != null) {
            Utils.sendLog(error.getMessage(), level, null);
        }
        if (message != null) {
            Utils.sendLog(message, level, null);
        }
    }

    protected void embedding(String mimeType, byte[] data) {
        File file = new File();
        String embeddingName;
        try {
            embeddingName = MimeTypes.getDefaultMimeTypes().forName(mimeType).getType().getType();
        } catch (MimeTypeException e) {
            LOGGER.warn("Mime-type not found", e);
            embeddingName = "embedding";
        }
        file.setName(embeddingName);
        file.setContent(data);
        Utils.sendLog(embeddingName, "UNKNOWN", file);
    }

    protected void write(String text) {
        Utils.sendLog(text, "INFO", null);
    }

    protected boolean isBefore(TestStep step) {
        return HookType.BEFORE == ((HookTestStep) step).getHookType();
    }

    protected abstract Maybe<String> getRootItemId();


    /**
     * Private part that responsible for handling events
     */

    private EventHandler<TestRunStarted> getTestRunStartedHandler() {
        return event -> beforeLaunch();
    }

    private EventHandler<TestSourceRead> getTestSourceReadHandler() {
        return event -> RunningContext.FeatureContext.addTestSourceReadEvent(event.getUri().toString(), event);
    }

    private EventHandler<TestCaseStarted> getTestCaseStartedHandler() {
        return this::handleStartOfTestCase;
    }

    private EventHandler<TestStepStarted> getTestStepStartedHandler() {
        return this::handleTestStepStarted;
    }

    private EventHandler<TestStepFinished> getTestStepFinishedHandler() {
        return this::handleTestStepFinished;
    }

    private EventHandler<TestCaseFinished> getTestCaseFinishedHandler() {
        return this::afterScenario;
    }

    private EventHandler<TestRunFinished> getTestRunFinishedHandler() {
        return event -> {
            if (currentFeatureContext != null) {
                handleEndOfFeature();
            }
            afterLaunch();
        };
    }

    private EventHandler<EmbedEvent> getEmbedEventHandler() {
        return event -> embedding(event.getMediaType(), event.getData());
    }

    private EventHandler<WriteEvent> getWriteEventHandler() {
        return event -> write(event.getText());
    }

    private void handleStartOfFeature(TestCase testCase) {
        currentFeatureContext = new RunningContext.FeatureContext().processTestSourceReadEvent(testCase);
        beforeFeature();
    }

    private void handleEndOfFeature() {
        afterFeature();
    }

    private void handleStartOfTestCase(TestCaseStarted event) {
        TestCase testCase = event.getTestCase();
        if (currentFeatureContext != null && !testCase.getUri().toString().equals(currentFeatureContext.getUri())) {
            handleEndOfFeature();
        }
        if (currentFeatureContext == null) {
            handleStartOfFeature(testCase);
        }
        if (!currentFeatureContext.getUri().equals(testCase.getUri().toString())) {
            throw new IllegalStateException("Scenario URI does not match Feature URI.");
        }
        if (currentScenarioContext == null) {
            currentScenarioContext = currentFeatureContext.getScenarioContext(testCase);
        }
        beforeScenario();
    }

    private void handleTestStepStarted(TestStepStarted event) {
        TestStep testStep = event.getTestStep();
        if (testStep instanceof HookTestStep) {
            beforeHooks(((HookTestStep) testStep).getHookType());
        } else {
            if (currentScenarioContext.withBackground()) {
                currentScenarioContext.nextBackgroundStep();
            }
            beforeStep(testStep);
        }
    }

    private void handleTestStepFinished(TestStepFinished event) {
        if (event.getTestStep() instanceof HookTestStep) {
            hookFinished(event.getTestStep(), event.getResult(), isBefore(event.getTestStep()));
            afterHooks(isBefore(event.getTestStep()));
        } else {
            afterStep(event.getResult());
        }
    }
}
