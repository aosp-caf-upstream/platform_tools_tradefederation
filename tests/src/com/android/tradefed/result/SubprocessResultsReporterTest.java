/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static org.junit.Assert.*;

import com.android.ddmlib.testrunner.TestIdentifier;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.SubprocessTestResultsParser;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.util.Collections;

/**
 * Unit Tests for {@link SubprocessResultsReporter}
 */
@RunWith(JUnit4.class)
public class SubprocessResultsReporterTest {

    private SubprocessResultsReporter mReporter;

    @Before
    public void setUp() {
        mReporter = new SubprocessResultsReporter();
    }

    /**
     * Test that when none of the option for reporting is set, nothing happen.
     */
    @Test
    public void testPrintEvent_Inop() {
        TestIdentifier testId = new TestIdentifier("com.fakeclass", "faketest");
        mReporter.testStarted(testId);
        mReporter.testFailed(testId, "fake failure");
        mReporter.testEnded(testId, Collections.emptyMap());
        mReporter.printEvent(null, null);
    }

    /**
     * Test that when a report file is specified it logs event to it.
     */
    @Test
    public void testPrintEvent_printToFile() throws Exception {
        OptionSetter setter = new OptionSetter(mReporter);
        File tmpReportFile = FileUtil.createTempFile("subprocess-reporter", "unittest");
        try {
            setter.setOptionValue("subprocess-report-file", tmpReportFile.getAbsolutePath());
            mReporter.testRunStarted("TEST", 5);
            mReporter.testRunEnded(100, Collections.emptyMap());
            String content = FileUtil.readStringFromFile(tmpReportFile);
            assertEquals("TEST_RUN_STARTED {\"testCount\":5,\"runName\":\"TEST\"}\n"
                    + "TEST_RUN_ENDED {\"time\":100}\n", content);
        } finally {
            FileUtil.deleteFile(tmpReportFile);
        }
    }

    /**
     * Test that when the specified report file is not writable we throw an exception.
     */
    @Test
    public void testPrintEvent_nonWritableFile() throws Exception {
        OptionSetter setter = new OptionSetter(mReporter);
        File tmpReportFile = FileUtil.createTempFile("subprocess-reporter", "unittest");
        try {
            tmpReportFile.setWritable(false);
            setter.setOptionValue("subprocess-report-file", tmpReportFile.getAbsolutePath());
            mReporter.testRunStarted("TEST", 5);
            fail("Should have thrown an exception.");
        } catch (RuntimeException expected) {
            assertEquals(String.format("report file: %s is not writable",
                    tmpReportFile.getAbsolutePath()), expected.getMessage());
        } finally {
            FileUtil.deleteFile(tmpReportFile);
        }
    }

    /**
     * Test that events sent through the socket reporting part are received on the other hand.
     */
    @Test
    public void testPrintEvent_printToSocket() throws Exception {
        TestIdentifier testId = new TestIdentifier("com.fakeclass", "faketest");
        ITestInvocationListener mMockListener = EasyMock.createMock(ITestInvocationListener.class);
        SubprocessTestResultsParser receiver = new SubprocessTestResultsParser(mMockListener, true);
        try {
            OptionSetter setter = new OptionSetter(mReporter);
            setter.setOptionValue("subprocess-report-port",
                    Integer.toString(receiver.getSocketServerPort()));
            // mirror calls between receiver and sender.
            mMockListener.testIgnored(testId);
            mMockListener.testAssumptionFailure(testId, "fake trace");
            mMockListener.testRunFailed("no reason");
            mMockListener.invocationFailed((Throwable)EasyMock.anyObject());
            EasyMock.replay(mMockListener);
            mReporter.testIgnored(testId);
            mReporter.testAssumptionFailure(testId, "fake trace");
            mReporter.testRunFailed("no reason");
            mReporter.invocationFailed(new Throwable());
            mReporter.close();
            receiver.joinReceiver(500);
            EasyMock.verify(mMockListener);
        } finally {
            receiver.close();
        }
    }
}
