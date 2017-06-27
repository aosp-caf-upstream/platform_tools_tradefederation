/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.media.tests;

import com.android.ddmlib.CollectingOutputReceiver;
import com.android.ddmlib.testrunner.TestIdentifier;
import com.android.tradefed.config.Option;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.IFileEntry;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.testtype.IDeviceTest;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.RunUtil;

import java.io.File;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tests adb command "screenrecord", i.e. "adb screenrecord [--size] [--bit-rate] [--time-limit]"
 *
 * <p>The test use the above command to record a video of DUT's screen. It then tries to verify that
 * a video was actually recorded and that the video is a valid video file. It currently uses
 * 'avprobe' to do the video analysis along with extracting parameters from the adb command's
 * output.
 */
public class AdbScreenrecordTest implements IDeviceTest, IRemoteTest {

    //===================================================================
    // TEST OPTIONS
    //===================================================================
    @Option(name = "run-key", description = "Run key for the test")
    private String mRunKey = "AdbScreenRecord";

    @Option(name = "time-limit", description = "Recording time in seconds", isTimeVal = true)
    private long mRecordTimeInSeconds = -1;

    @Option(name = "size", description = "Video Size: 'widthxheight', e.g. '1280x720'")
    private String mVideoSize = null;

    @Option(name = "bit-rate", description = "Video bit rate in megabits per second, e.g. 4000000")
    private long mBitRate = -1;

    //===================================================================
    // CLASS VARIABLES
    //===================================================================
    private ITestDevice mDevice;
    private TestIdentifier mTestId;

    //===================================================================
    // CONSTANTS
    //===================================================================
    private static final long TEST_TIMEOUT_MS = 5 * 60 * 1000; // 5 min
    private static final long DEVICE_SYNC_MS = 5 * 60 * 1000; // 5 min
    private static final long POLLING_INTERVAL_MS = 5 * 1000; // 5 sec
    private static final long CMD_TIMEOUT_MS = 5 * 1000; // 5 sec
    private static final String ERR_OPTION_MALFORMED = "Test option %1$s is not correct [%2$s]";
    private static final String OPTION_TIME_LIMIT = "--time-limit";
    private static final String OPTION_SIZE = "--size";
    private static final String OPTION_BITRATE = "--bit-rate";
    private static final String RESULT_KEY_RECORDED_FRAMES = "recorded_frames";
    private static final String RESULT_KEY_RECORDED_LENGTH = "recorded_length";
    private static final String RESULT_KEY_VERIFIED_DURATION = "verified_duration";
    private static final String RESULT_KEY_VERIFIED_BITRATE = "verified_bitrate";
    private static final String TEST_FILE = "/sdcard/screenrecord_test.mp4";
    private static final String AVPROBE_NOT_INSTALLED =
            "Program 'avprobe' is not installed on host '%1$s'";
    private static final String REGEX_IS_VIDEO_OK =
            "Duration: (\\d\\d:\\d\\d:\\d\\d.\\d\\d).+bitrate: (\\d+ .b\\/s)";
    private static final String AVPROBE_STR = "avprobe";

    //===================================================================
    // ENUMS
    //===================================================================
    enum HOST_SOFTWARE {
        AVPROBE
    }

    @Override
    public void setDevice(ITestDevice device) {
        mDevice = device;
    }

    @Override
    public ITestDevice getDevice() {
        return mDevice;
    }

    /** Main test function invoked by test harness */
    @Override
    public void run(ITestInvocationListener listener) throws DeviceNotAvailableException {
        initializeTest(listener);

        CLog.i("Verify that test options are valid");
        if (!verifyTestParameters(listener, mTestId)) {
            return;
        }

        CLog.i("Verify required software is installed on host");
        verifyRequiredSoftwareIsInstalled(HOST_SOFTWARE.AVPROBE);

        final long testStartTime = System.currentTimeMillis();

        final Map<String, String> resultsDictionary = runTest(listener, TEST_TIMEOUT_MS);

        final long testStopTime = System.currentTimeMillis();

        // "resultDictionary" can be used to post results to dashboards like BlackBox
        listener.testEnded(mTestId, resultsDictionary);
        listener.testRunEnded(testStopTime - testStartTime, resultsDictionary);
    }

    /**
     * Test code that calls "adb screenrecord" and checks for pass/fail criterias
     *
     * <p>
     *
     * <ul>
     *   <li>1. Run adb screenrecord command
     *   <li>2. Wait until there is a video file; fail if none appears
     *   <li>3. Analyze adb output and extract recorded number of frames and video length
     *   <li>4. Pull recorded video file off device
     *   <li>5. Using avprobe, analyze video file and extract duration and bitrate
     *   <li>6. Return extracted results
     * </ul>
     */
    private Map<String, String> runTest(final ITestInvocationListener listener, final long timeout)
            throws DeviceNotAvailableException {
        final Map<String, String> results = new HashMap<String, String>();
        final CollectingOutputReceiver receiver = new CollectingOutputReceiver();
        final String cmd = generateAdbScreenRecordCommand();
        final String deviceFileName = getAbsoluteFilename();

        CLog.i("START Execute device shell command: '" + cmd + "'");
        getDevice().executeShellCommand(cmd, receiver, timeout, TimeUnit.MILLISECONDS, 3);
        String adbOutput = receiver.getOutput();
        CLog.i(adbOutput);
        CLog.i("END Execute device shell command");

        CLog.i("Wait for recorded file: " + deviceFileName);
        if (!waitForFile(getDevice(), timeout, deviceFileName)) {
            reportFailure(listener, mTestId, "Recorded test file not found");
            // Since we don't have a file, no need to delete it; we can return here
            return results;
        }

        CLog.i("Get number of recorded frames and recorded length from adb output");
        if (!extractVideoDataFromAdbOutput(listener, adbOutput, results)) {
            deleteFileFromDevice(deviceFileName);
            return results;
        }

        CLog.i("Get duration and bitrate info from video file using '" + AVPROBE_STR + "'");
        extractDurationAndBitrateFromVideoFileUsingAvprobe(listener, deviceFileName, results);
        deleteFileFromDevice(deviceFileName);
        return results;
    }

    /**
     * Deletes a file off a device
     *
     * @param deviceFileName - path and filename to file to be deleted
     * @throws DeviceNotAvailableException
     */
    private void deleteFileFromDevice(String deviceFileName) throws DeviceNotAvailableException {
        CLog.i("Delete file from device: " + deviceFileName);
        getDevice().executeShellCommand("rm -f " + deviceFileName);
    }

    /**
     * Extracts duration and bitrate data from a video file
     *
     * @throws DeviceNotAvailableException
     */
    private boolean extractDurationAndBitrateFromVideoFileUsingAvprobe(
            ITestInvocationListener listener, String deviceFileName, Map<String, String> results)
            throws DeviceNotAvailableException {
        CLog.i("Check if the recorded file has some data in it: " + deviceFileName);
        IFileEntry video = getDevice().getFileEntry(deviceFileName);
        if (video == null || video.getFileEntry().getSizeValue() < 1) {
            reportFailure(listener, mTestId, "Video Entry info failed");
            return false;
        }

        final File recordedVideo = getDevice().pullFile(deviceFileName);
        CLog.i("Recorded video file: " + recordedVideo.getAbsolutePath());

        CommandResult result =
                RunUtil.getDefault()
                        .runTimedCmd(
                                CMD_TIMEOUT_MS,
                                AVPROBE_STR,
                                "-loglevel",
                                "info",
                                recordedVideo.getAbsolutePath());

        // Remove file from host machine
        FileUtil.deleteFile(recordedVideo);

        if (result.getStatus() != CommandStatus.SUCCESS) {
            reportFailure(listener, mTestId, AVPROBE_STR + " command failed");
            return false;
        }

        String data = result.getStderr();
        CLog.i("data: " + data);
        if (data == null || data.isEmpty()) {
            reportFailure(listener, mTestId, AVPROBE_STR + " output data is empty");
            return false;
        }

        Matcher m = Pattern.compile(REGEX_IS_VIDEO_OK).matcher(data);
        if (!m.find()) {
            final String errMsg =
                    "Video verification failed; no matching verification pattern found";
            reportFailure(listener, mTestId, errMsg);
            return false;
        }

        results.put(RESULT_KEY_VERIFIED_DURATION, m.group(1));
        results.put(RESULT_KEY_VERIFIED_BITRATE, m.group(2));
        return true;
    }

    /** Extracts recorded number of frames and recorded video length from adb output */
    private boolean extractVideoDataFromAdbOutput(
            ITestInvocationListener listener, String adbOutput, Map<String, String> results) {
        final String regEx = "recorded (\\d+) frames in (\\d+) second";
        Matcher m = Pattern.compile(regEx).matcher(adbOutput);
        if (!m.find()) {
            reportFailure(listener, mTestId, "Regular Expression did not find recorded frames");
            return false;
        }

        int recordedFrames = Integer.parseInt(m.group(1));
        int recordedLength = Integer.parseInt(m.group(2));
        CLog.i("Recorded frames: " + recordedFrames);
        CLog.i("Recorded length: " + recordedLength);
        if (recordedFrames <= 0) {
            reportFailure(listener, mTestId, "No recorded frames detected");
            return false;
        }

        results.put(RESULT_KEY_RECORDED_FRAMES, Integer.toString(recordedFrames));
        results.put(RESULT_KEY_RECORDED_LENGTH, Integer.toString(recordedLength));
        return true;
    }

    /** Generates an adb command from passed in test options */
    private String generateAdbScreenRecordCommand() {
        final String SPACE = " ";
        StringBuilder sb = new StringBuilder(128);
        sb.append("screenrecord --verbose ").append(getAbsoluteFilename());

        // Add test options if they have been passed in to the test
        if (mRecordTimeInSeconds != -1) {
            final long timeLimit = TimeUnit.MILLISECONDS.toSeconds(mRecordTimeInSeconds);
            sb.append(SPACE).append(OPTION_TIME_LIMIT).append(SPACE).append(timeLimit);
        }

        if (mVideoSize != null) {
            sb.append(SPACE).append(OPTION_SIZE).append(SPACE).append(mVideoSize);
        }

        if (mBitRate != -1) {
            sb.append(SPACE).append(OPTION_BITRATE).append(SPACE).append(mBitRate);
        }

        return sb.toString();
    }

    /** Returns absolute path to device recorded video file */
    private String getAbsoluteFilename() {
        return TEST_FILE;
    }

    /** Performs test initialization steps */
    private void initializeTest(ITestInvocationListener listener)
            throws UnsupportedOperationException, DeviceNotAvailableException {
        mTestId = new TestIdentifier(getClass().getCanonicalName(), mRunKey);

        getDevice().unlockDevice();
        getDevice().waitForDeviceAvailable(DEVICE_SYNC_MS);

        CLog.i("Sync device time to host time");
        getDevice().setDate(new Date());

        listener.testRunStarted(mRunKey, 1);
        listener.testStarted(mTestId);
    }

    /** Reports test failure with error message */
    private static void reportFailure(
            ITestInvocationListener listener, TestIdentifier testId, String errMsg) {
        CLog.e(errMsg);
        listener.testFailed(testId, errMsg);
        listener.testEnded(testId, new HashMap<String, String>());
        listener.testRunFailed(errMsg);
    }

    /** Verifies that required software is installed on host machine */
    private void verifyRequiredSoftwareIsInstalled(HOST_SOFTWARE software) {
        String swName = "";
        switch (software) {
            case AVPROBE:
                swName = AVPROBE_STR;
                CommandResult result =
                        RunUtil.getDefault().runTimedCmd(CMD_TIMEOUT_MS, swName, "-version");
                String output = result.getStdout();
                if (result.getStatus() == CommandStatus.SUCCESS && output.startsWith(swName)) {
                    return;
                }
                break;
        }

        CLog.i("Program '" + swName + "' not found, report test failure");
        String hostname = RunUtil.getDefault().runTimedCmd(CMD_TIMEOUT_MS, "hostname").getStdout();

        String err = String.format(AVPROBE_NOT_INSTALLED, (hostname == null) ? "" : hostname);
        throw new RuntimeException(err);
    }

    /** Verifies that passed in test parameters are legitimate */
    private boolean verifyTestParameters(
            ITestInvocationListener listener, final TestIdentifier testId) {

        if (mRecordTimeInSeconds != -1 && mRecordTimeInSeconds < 1) {
            final String error =
                    String.format(ERR_OPTION_MALFORMED, OPTION_TIME_LIMIT, mRecordTimeInSeconds);
            reportFailure(listener, testId, error);
            return false;
        }

        if (mVideoSize != null) {
            final String videoSizeRegEx = "\\d+x\\d+";
            Matcher m = Pattern.compile(videoSizeRegEx).matcher(mVideoSize);
            if (!m.matches()) {
                final String error = String.format(ERR_OPTION_MALFORMED, OPTION_SIZE, mVideoSize);
                reportFailure(listener, testId, error);
                return false;
            }
        }

        if (mBitRate != -1 && mBitRate < 1) {
            final String error = String.format(ERR_OPTION_MALFORMED, OPTION_BITRATE, mBitRate);
            reportFailure(listener, testId, error);
            return false;
        }

        return true;
    }

    /** Checks for existence of a file on the device */
    private static boolean waitForFile(
            ITestDevice device, final long timeout, final String absoluteFilename)
            throws DeviceNotAvailableException {
        final long checkFileStartTime = System.currentTimeMillis();

        do {
            RunUtil.getDefault().sleep(POLLING_INTERVAL_MS);
            if (device.doesFileExist(absoluteFilename)) {
                return true;
            }
        } while (System.currentTimeMillis() - checkFileStartTime < timeout);

        return false;
    }
}