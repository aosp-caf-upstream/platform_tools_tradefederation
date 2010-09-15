/*
 * Copyright (C) 2010 The Android Open Source Project
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
package com.android.tradefed.result;

import com.android.ddmlib.testrunner.TestIdentifier;
import com.android.tradefed.result.TestResult.TestStatus;
import com.android.tradefed.targetsetup.IBuildInfo;

import java.io.InputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A {@link ITestInvocationListener} that will collect all test results.
 * <p/>
 * Although the data structures used in this object are thread-safe, the
 * {@link ITestInvocationListener} callbacks must be called in the correct order.
 */
public class CollectingTestListener implements ITestInvocationListener {

    // Stores the test results
    // Uses a synchronized map to make thread safe.
    // Uses a LinkedHashmap to have predictable iteration order
    private Map<String, TestRunResult> mRunResultsMap =
        Collections.synchronizedMap(new LinkedHashMap<String, TestRunResult>());
    private TestRunResult mCurrentResults = null;

    // cached test constants
    private Integer mNumTotalTests = null;
    private Integer mNumPassedTests = null;
    private Integer mNumFailedTests = null;
    private Integer mNumErrorTests = null;

    /**
     * {@inheritDoc}
     */
    public void invocationStarted(IBuildInfo buildInfo) {
        // ignore
    }

    /**
     * {@inheritDoc}
     */
    public void testRunStarted(String name, int numTests) {
        if (mRunResultsMap.containsKey(name)) {
            // rerun of previous run. Add test results to it
            mCurrentResults = mRunResultsMap.get(name);
        } else {
            // new run
            mCurrentResults = new TestRunResult(name);
            mRunResultsMap.put(name, mCurrentResults);
        }
        mCurrentResults.setRunComplete(false);
        mCurrentResults.setRunFailed(false);
    }

    /**
     * {@inheritDoc}
     */
    public void testStarted(TestIdentifier test) {
        // ignore
    }

    /**
     * {@inheritDoc}
     */
    public void testEnded(TestIdentifier test, Map<String, String> testMetrics) {
        if (mCurrentResults == null) {
            throw new IllegalStateException("testEnded called before testRunStarted");
        }
        // only record test pass if failure not already recorded
        if (!mCurrentResults.getTestResults().containsKey(test)) {
            mCurrentResults.getTestResults().put(test, new TestResult(TestStatus.PASSED));
        }
    }

    /**
     * {@inheritDoc}
     */
    public void testFailed(TestFailure status, TestIdentifier test, String trace) {
        if (mCurrentResults == null) {
            throw new IllegalStateException("testFailed called before testRunStarted");
        }
        if (status.equals(TestFailure.ERROR)) {
            mCurrentResults.getTestResults().put(test, new TestResult(TestStatus.ERROR, trace));
        } else {
            mCurrentResults.getTestResults().put(test, new TestResult(TestStatus.FAILURE, trace));
        }
    }

    /**
     * {@inheritDoc}
     */
    public void testRunEnded(long elapsedTime, Map<String, String> runMetrics) {
        if (mCurrentResults == null) {
            throw new IllegalStateException("testRunEnded called before testRunStarted");
        }
        mCurrentResults.setRunComplete(true);
        mCurrentResults.setMetrics(runMetrics);
        mCurrentResults.addElapsedTime(elapsedTime);
    }

    /**
     * {@inheritDoc}
     */
    public void testRunFailed(String errorMessage) {
        if (mCurrentResults == null) {
            throw new IllegalStateException("testRunFailed called before testRunStarted");
        }
        mCurrentResults.setRunComplete(true);
        mCurrentResults.setRunFailed(true);
    }

    /**
     * {@inheritDoc}
     */
    public void testRunStopped(long elapsedTime) {
        if (mCurrentResults == null) {
            throw new IllegalStateException("testRunStopped called before testRunStarted");
        }
        mCurrentResults.setRunComplete(true);
        mCurrentResults.addElapsedTime(elapsedTime);
    }

    /**
     * Gets the results for the current test run.
     */
    public TestRunResult getCurrentRunResults() {
        return mCurrentResults;
    }

    /**
     * Gets the results for all test runs.
     */
    public Collection<TestRunResult> getRunResults() {
        return mRunResultsMap.values();
    }

    /**
     * Gets the total number of tests for all runs.
     */
    public int getNumTotalTests() {
        if (!areTestCountsCalculated()) {
            calculateTestCounts();
        }
        return mNumTotalTests;
    }

    /**
     * Gets the total number of failed tests for all runs.
     */
    public int getNumFailedTests() {
        if (!areTestCountsCalculated()) {
            calculateTestCounts();
        }
        return mNumFailedTests;
    }

    /**
     * Gets the total number of error tests for all runs.
     */
    public int getNumErrorTests() {
        if (!areTestCountsCalculated()) {
            calculateTestCounts();
        }
        return mNumErrorTests;
    }

    /**
     * Gets the total number of passed tests for all runs.
     */
    public int getNumPassedTests() {
        if (!areTestCountsCalculated()) {
            calculateTestCounts();
        }
        return mNumPassedTests;
    }

    /**
     * @returns true if invocation had any failed or error tests.
     */
    public boolean hasFailedTests() {
        return getNumErrorTests() > 0 || getNumFailedTests() > 0;
    }

    private synchronized boolean areTestCountsCalculated() {
        return mNumTotalTests != null;
    }

    private synchronized void calculateTestCounts() {
        mNumTotalTests = 0;
        mNumPassedTests = 0;
        mNumFailedTests = 0;
        mNumErrorTests = 0;
        for (TestRunResult runResult : getRunResults()) {
            mNumTotalTests += runResult.getNumTests();
            mNumPassedTests += runResult.getNumPassedTests();
            mNumFailedTests += runResult.getNumFailedTests();
            mNumErrorTests += runResult.getNumErrorTests();
        }
    }

    /**
     * {@inheritDoc}
     */
    public void invocationEnded(long elapsedTime) {
        // ignore
    }

    /**
     * {@inheritDoc}
     */
    public void invocationFailed(Throwable cause) {
        // ignore
    }

    /**
     * {@inheritDoc}
     */
    public TestSummary getSummary() {
        // ignore
        return null;
    }

    /**
     * {@inheritDoc}
     */
    public void testLog(String dataName, LogDataType dataType, InputStream dataStream) {
        // ignore
    }
}
