/* Copyright 2019 The TensorFlow Authors. All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
==============================================================================*/

/* Changes from QuIC are provided under the following license:

Copyright (c) 2022, Qualcomm Innovation Center, Inc. All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice,
   this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its contributors
   may be used to endorse or promote products derived from this software
   without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
POSSIBILITY OF SUCH DAMAGE.

SPDX-License-Identifier: BSD-3-Clause
==============================================================================*/

package com.qualcomm.qti.qa.ml;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.AssetManager;
import androidx.annotation.WorkerThread;
import android.util.Log;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;


import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.Socket;

import com.google.common.base.Joiner;
import com.google.gson.JsonObject;
import com.qualcomm.qti.qa.ui.QaActivity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.LinkedList;

import java.io.DataOutputStream;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import com.qualcomm.qti.qa.ml.MLFQ.Model;


/** Interface to load SNPE model and provide predictions. */
public class QaClient {
    private static final String TAG = "SNPE_Client";
    private static final String DIC_PATH = "vocab.txt";
    private static final int MAX_ANS_LEN = 32;
    private static final int MAX_QUERY_LEN = 64;
    private static final int MAX_SEQ_LEN = 384;
    private static final boolean DO_LOWER_CASE = true;
    private static final int PREDICT_ANS_NUM = 3; // default 5; can be set to 3 without issues
    private static final int OUTPUT_OFFSET = 1;
    private static final Joiner SPACE_JOINER = Joiner.on(" ");
    private static boolean doSnpeInit = true;

    private final Context context;
    private final Map<String, Integer> dic = new HashMap<>();
    private final FeatureConverter featureConverter;
    private AssetManager assetManager;

    private static final String API_URL = "https://api.aimlapi.com/v1";
    private static final String API_KEY = "03b1da0aa2f243a38eb3d55fd9f8711b";
    private static final String MODEL = "mistralai/Mistral-7B-Instruct-v0.2";
    private static final String SYSTEM_PROMPT = "You are a travel agent. Be descriptive and helpful";
    
    private static final double ALPHA = 0.25;

    private static final int PRIORITY_HIGH = 0;
    private static final int PRIORITY_MID = 1;
    private static final int PRIORITY_LOW = 2;

    private static final int ElectraNo = 0;
    private static final int BertNo = 1;
    private static final int OllamaNo = 2;
    private static final int GeminiNo = 3;

    private static Model prevModel;
    private static float prevCpuUsage = 0.0f;
    private static float prevBatteryConsumption = 0.0f;

    private static int agingFactor = 3;
    private static int runsSinceLastBoost = 0;
    private static int boostInterval = 5;
    private static int tokenThreshold = 300;

    private final QaAnswerCache answerCache;
    private MLFQ queues;
    public static boolean displayCache = false;

    public QaClient(Context context) {
        this.context = context;
        this.featureConverter = new FeatureConverter(dic, DO_LOWER_CASE, MAX_QUERY_LEN, MAX_SEQ_LEN);
        this.answerCache = QaAnswerCache.getInstance();
        this.queues = MLFQ.getInstance();
    }

    private void promoteModel(Model model) {
        if(model.priority > PRIORITY_HIGH) {
            model.priority--;
            moveModelToQueue(model);
        }
    }

    private void demoteModel(Model model) {
        if(model.priority < PRIORITY_LOW) {
            model.priority++;
            moveModelToQueue(model);
        }
    }

    private void moveModelToQueue(Model model) {
        queues.highPriorityQueue.remove(model);
        queues.midPriorityQueue.remove(model);
        queues.lowPriorityQueue.remove(model);
        switch(model.priority) {
            case PRIORITY_HIGH:
                queues.highPriorityQueue.add(model);
                break;
            case PRIORITY_MID:
                queues.midPriorityQueue.add(model);
                break;
            case PRIORITY_LOW:
                queues.lowPriorityQueue.add(model);
                break;
        }
    }

    private Model getModelByNumber(int number) {
        for(Model model : queues.highPriorityQueue) {
            if(model.number == number) return model;
        }
        for(Model model : queues.midPriorityQueue) {
            if (model.number == number) return model;
        }
        for(Model model : queues.lowPriorityQueue) {
            if (model.number == number) return model;
        }
        return null;
    }

    private Model selectModel() {
        if(!queues.highPriorityQueue.isEmpty()) return queues.highPriorityQueue.peek();
        if(!queues.midPriorityQueue.isEmpty()) return queues.midPriorityQueue.peek();
        return queues.lowPriorityQueue.peek();
    }

    private double calculateScore(int batteryLevel, float batteryConsumption, float temperature, float cpuUsage, float tokenFactor, double feedback, float eFactor) {
        double weightBattery = (batteryLevel < 30) ? 0.4 : 0.2;
        double weightCpuUsage = (temperature > 40) ? 0.4 : 0.2;
        double weightFeedback = Math.round((1.0 - weightBattery - weightCpuUsage)* 10) / 10.0;

        //normalized scores:
        double batteryScore = 1.0 - (batteryConsumption / 100.0);
        double cpuScore = 1.0 - (cpuUsage / 100.0);

        double score = (batteryScore * weightBattery) + (cpuScore * weightCpuUsage) + (feedback * weightFeedback);
        Log.v("Calculating...", "eFactor:" + eFactor + " tokenFactor:" +tokenFactor);
        Log.v("battery", " batteryScore:"+batteryScore+ " weightBattery:" + weightBattery);
        Log.v("cpu"," cpuScore:"+cpuScore+ " weightCpu:" + weightCpuUsage );
        Log.v("feedback"," feedback:"+feedback+ " weightFeedback" + weightFeedback);
        return eFactor * tokenFactor * score;
    }

    public void updateEMA(double newScore){
        queues.emaScore = ALPHA * newScore + (1 - ALPHA) * queues.emaScore;
    }


    // Constructor without Context for non-Android use cases
//    public QaClient() {
//        this.featureConverter = new FeatureConverter(dic, DO_LOWER_CASE, MAX_QUERY_LEN, MAX_SEQ_LEN);
//    }


    static {
        System.loadLibrary("qa");
    }

    @WorkerThread
    public synchronized String loadModel() {
        String uiLogger = "";
        try {
            if (doSnpeInit) {
                String nativeDirPath = context.getApplicationInfo().nativeLibraryDir;
                uiLogger += queryRuntimes(nativeDirPath);
                assetManager = context.getAssets();
                Log.i(TAG, "onCreate: Initializing SNPE ...");
                uiLogger = initSNPE(assetManager);
                doSnpeInit = false;
            }
        } catch (Exception ex) {
            Log.e(TAG, ex.getMessage());
            uiLogger += ex.getMessage();
        }
        return uiLogger;
    }

    @WorkerThread
    public synchronized void loadDictionary() {
        try {
            loadDictionaryFile(this.context.getAssets());
            Log.v(TAG, "Dictionary loaded.");
        } catch (IOException ex) {
            Log.e(TAG, ex.getMessage());
        }
    }

    @WorkerThread
    public synchronized void unload() {
        dic.clear();
    }

    public void loadDictionaryFile(AssetManager assetManager) throws IOException {
        try (InputStream ins = assetManager.open(DIC_PATH);
             BufferedReader reader = new BufferedReader(new InputStreamReader(ins))) {
            int index = 0;
            while (reader.ready()) {
                String key = reader.readLine();
                dic.put(key, index++);
            }
        }
    }

    @WorkerThread
    public synchronized List<QaAnswer> predict(String query, String content, String runtime, StringBuilder execStatus) {
        Log.v(TAG, "Convert Feature...");
        Feature feature = featureConverter.convert(query, content);
        Log.v(TAG, "Set inputs...");

        float[][] inputIds = new float[1][MAX_SEQ_LEN];
        int[][] inpIds = new int[1][MAX_SEQ_LEN];
        float[][] inputMask = new float[1][MAX_SEQ_LEN];
        float[][] segmentIds = new float[1][MAX_SEQ_LEN];
        float[][] startLogits = new float[1][MAX_SEQ_LEN];
        float[][] endLogits = new float[1][MAX_SEQ_LEN];

        for (int j = 0; j < MAX_SEQ_LEN; j++) {
            inputIds[0][j] = feature.inputIds[j];
            inpIds[0][j] = feature.inputIds[j];
            inputMask[0][j] = feature.inputMask[j];
            segmentIds[0][j] = feature.segmentIds[j];
        }

        Map<Integer, Object> output = new HashMap<>();
        output.put(0, startLogits);
        output.put(1, endLogits);

        Log.v(TAG, "Run inference...");
        if (runtime.equals("DSP")) {
            Log.i(TAG, "Sending Inf request to SNPE DSP");
            long htpSTime = System.currentTimeMillis();
            String dsp_logs = inferSNPE(runtime, inputIds[0], inputMask[0], segmentIds[0], MAX_SEQ_LEN, startLogits[0], endLogits[0]);
            long htpETime = System.currentTimeMillis();
            long htpTime = htpETime - htpSTime;
            Log.i(TAG, "DSP Exec took : " + htpTime + "ms");
            if (!dsp_logs.isEmpty()) {
                Log.i(TAG, "DSP Exec status : " + dsp_logs);
                execStatus.append(dsp_logs);
            }
        } else {
            Log.i(TAG, "Sending Inf request to SNPE CPU");
            String cpu_logs = inferSNPE(runtime, inputIds[0], inputMask[0], segmentIds[0], MAX_SEQ_LEN, startLogits[0], endLogits[0]);
            if (!cpu_logs.isEmpty()) {
                Log.i(TAG, "CPU Exec status : " + cpu_logs);
                execStatus.append(cpu_logs);
            }
        }

        Log.v(TAG, "Convert logits to answers...");
        List<QaAnswer> answers = getBestAnswers(startLogits[0], endLogits[0], feature);
        Log.v(TAG, "Finish.");
        return answers;
    }

    public synchronized List<QaAnswer> predictBert(String query, String content, String runtime, StringBuilder execStatus) {
        Log.v(TAG, "Convert Feature...");
        Feature feature = featureConverter.convert(query, content);
        Log.v(TAG, "Set inputs...");

        // Setup input arrays for inference
        float[][] inputIds = new float[1][MAX_SEQ_LEN];
        int[][] inpIds = new int[1][MAX_SEQ_LEN];
        float[][] inputMask = new float[1][MAX_SEQ_LEN];
        float[][] segmentIds = new float[1][MAX_SEQ_LEN];
        float[][] startLogits = new float[1][MAX_SEQ_LEN];
        float[][] endLogits = new float[1][MAX_SEQ_LEN];

        // Fill input arrays
        for (int j = 0; j < MAX_SEQ_LEN; j++) {
            inputIds[0][j] = feature.inputIds[j];
            inpIds[0][j] = feature.inputIds[j];
            inputMask[0][j] = feature.inputMask[j];
            segmentIds[0][j] = feature.segmentIds[j];
        }

        // Output map for storing logits
        Map<Integer, Object> output = new HashMap<>();
        output.put(0, startLogits);
        output.put(1, endLogits);

        // Run inference (SNPE or CPU, depending on runtime)
        if (runtime.equals("DSP")) {
            Log.i(TAG, "Sending Inf request to SNPE DSP");
            String dsp_logs = inferSNPE(runtime, inputIds[0], inputMask[0], segmentIds[0], MAX_SEQ_LEN, startLogits[0], endLogits[0]);
            if (!dsp_logs.isEmpty()) {
                Log.i(TAG, "DSP Exec status: " + dsp_logs);
                execStatus.append(dsp_logs);
            }
        } else {
            Log.i(TAG, "Sending Inf request to SNPE CPU");
            String cpu_logs = inferSNPE(runtime, inputIds[0], inputMask[0], segmentIds[0], MAX_SEQ_LEN, startLogits[0], endLogits[0]);
            if (!cpu_logs.isEmpty()) {
                Log.i(TAG, "CPU Exec status: " + cpu_logs);
                execStatus.append(cpu_logs);
            }
        }



        // Convert logits to answers using the getBestAnswers method
        Log.v(TAG, "Convert logits to answers...");
        List<QaAnswer> customAnswers = getBestAnswers(startLogits[0], endLogits[0], feature);

        // Return answers or empty list if no valid answers
        return customAnswers.isEmpty() ? Collections.emptyList() : customAnswers;
    }


    public String getAnswer(String question, String content, double userFeedback, boolean firstQuestion) {
        int batteryLevel = 0;
        float temperature = 0.0f;

        if(!firstQuestion) {
            batteryLevel = getBatteryLevel(context);
            temperature = getBatteryTemperature();
            int tokenCount = getTokenCount(content);
            double prevScore = calculateScore(batteryLevel, prevBatteryConsumption, temperature, prevCpuUsage, prevModel.tokenFactor, userFeedback, prevModel.eFactor);

            String message = "" + prevCpuUsage + " " + temperature + " " + batteryLevel + " " + prevModel.name + " "+ prevModel.type + " " + userFeedback + " " + prevScore + " ";
            sendData(message);


            if(userFeedback == 0.0) {
                prevModel.timesRejected++;
                Log.d("rejection", prevModel.name + " has been rejected " + prevModel.timesRejected + " times.");
            }

            if(prevModel.timesRejected >= 3){
                demoteModel(prevModel);
                Log.d("prevModel ", prevModel.name + " demoted to priority " + prevModel.priority);
                prevModel.executionCount = 0;
                prevModel.timesRejected = 0;
            }
            if (prevScore < queues.emaScore - 0.1 && prevModel.priority != PRIORITY_LOW) {
                demoteModel(prevModel);
                Log.d("prevModel ", prevModel.name + " demoted to priority " + prevModel.priority);
                prevModel.executionCount = 0;
            }
            else if(prevScore > queues.emaScore + 0.1 && prevModel.priority != PRIORITY_HIGH && userFeedback > 0.0){
                promoteModel(prevModel);
                Log.d("prevModel ", prevModel.name + " promoted to priority " + prevModel.priority);
            }
            else if(prevModel.executionCount > (3 - prevModel.priority) * agingFactor){
                moveModelToQueue(prevModel);
                Log.d("prevModel ", prevModel.name + " pushed back in queue.");
            }

            Log.v("Metrics", "EMA:" + queues.emaScore + " Model scored:" + prevScore);

            if(queues.emaScore == 0.0)
                queues.emaScore = prevScore;
            else
                updateEMA(prevScore);
        }

        QaActivity.beforeTime = System.currentTimeMillis();
        if (answerCache.hasAnswer(content, question)) {
            String cachedAnswer = answerCache.getAnswer(content, question);
            QaActivity.firstQuestion = true;
            Log.d("QaClient", "Cache hit for question: " + question);
            QaActivity.afterTime = System.currentTimeMillis();
            final int finalBatteryLevel = batteryLevel;
            final float finalCpuUsage = prevCpuUsage;

            if (qaActivityCallback != null) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    qaActivityCallback.onMetricsUpdated(finalBatteryLevel, finalCpuUsage, -1);
                });
            }
            displayCache = true;
            return cachedAnswer;
        }

        boolean Adapt;
        int modelToUse = QaActivity.modelToUse;
        Adapt = QaActivity.Adapt;
        Model selectedModel;
        if(!Adapt){
            selectedModel = getModelByNumber(modelToUse);
        }
        else
            selectedModel = selectModel();

        if(selectedModel.type.equals("Cloud") && getTokenCount(content) > tokenThreshold)
            selectedModel.tokenFactor = 2.0f;
        else
            selectedModel.tokenFactor = 1.0f;

        String answer = "No answer found.";

        if(!firstQuestion && prevModel != selectedModel) {
            prevModel.executionCount = 0;
            prevModel.timesRejected = 0;
        }

        Log.v("ModelSelection", "Using "+selectedModel.name+" (" + selectedModel.type + ")");
        QaActivity.beforeTime = System.currentTimeMillis();
        if(selectedModel.number == GeminiNo)
            answer = callLlamaApi(question, content);  // Concatenate question and content
        else if(selectedModel.number == OllamaNo)
            answer = callOllamaApi(question, content);
        else if(selectedModel.number == BertNo){
            StringBuilder execStatus = new StringBuilder();
            List<QaAnswer> answers = predictBert(question, content, "DSP", execStatus);
            answer = answers.isEmpty() ? "No answer found." : answers.get(0).text;
        } else{
            StringBuilder execStatus = new StringBuilder();
            List<QaAnswer> answers = predict(question, content, "DSP", execStatus);
            answer = answers.isEmpty() ? "No answer found." : answers.get(0).text;
        }
        QaActivity.afterTime = System.currentTimeMillis();

        if(answer.equals("No answer found."))
            selectedModel.timesRejected = 3;
        selectedModel.executionCount++;
        Log.d("Simult runs", selectedModel.name + " has run " + selectedModel.executionCount + " times simultaneously.");
        batteryLevel = getBatteryLevel(context);
        prevCpuUsage = getCpuUsage();
        prevBatteryConsumption = prevCpuUsage * batteryLevel / 100.0f;

        // Ensure the variables are not modified after assignment.
        final int finalBatteryLevel = batteryLevel;
        final float finalCpuUsage = prevCpuUsage;
        final int finalModelToUse = selectedModel.number;

        if (qaActivityCallback != null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                qaActivityCallback.onMetricsUpdated(finalBatteryLevel, finalCpuUsage, finalModelToUse);
            });
        }

        prevModel = selectedModel;
        runsSinceLastBoost++;


        if(runsSinceLastBoost > boostInterval){
            Log.d("Priority boost", "Priority boost!");
            while(!queues.midPriorityQueue.isEmpty()){
                Model model = queues.midPriorityQueue.poll();
                queues.highPriorityQueue.add(model);
                model.executionCount = 0;
                model.timesRejected = 0;
            };
            while(!queues.lowPriorityQueue.isEmpty()){
                Model model = queues.lowPriorityQueue.poll();
                queues.highPriorityQueue.add(model);
                model.executionCount = 0;
                model.timesRejected = 0;
            }
            runsSinceLastBoost = 0;
        }
        answerCache.putAnswer(content, question, answer);
        return answer;
    }

    // Method to call Ollama API
    private String callOllamaApi(String question, String content) {
        String serverAddress = "192.168.163.36";  // Address of (Ollama) Python server (Change to IP of server)
        int serverPort = 12345;

        try (Socket socket = new Socket(serverAddress, serverPort);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            // Send a message to Python server
            String message = String.format("Here is the question :%s And answer based on this short text (you may extend the context if need be): %s. Give response in 30 or lesser words",
                    question,
                    content
            );
            out.println(message);

            // Receive response from Python server
            String response = in.readLine();
            return response;

        } catch (IOException e) {
            e.printStackTrace();
            return "No answer found.";
        }
    }


    private QaActivityCallback qaActivityCallback;

    public interface QaActivityCallback {
        void onMetricsUpdated(int batteryLevel, float cpuUsage, int modelToUse);
    }

    public void setQaActivityCallback(QaActivityCallback callback) {
        this.qaActivityCallback = callback;
    }

    private int getTokenCount(String content) {
        // Assuming whitespace-based tokenization for simplicity
        return content.length() / 5;
    }

    public void sendData(String message) {
        try(Socket socket = new Socket("192.168.163.36", 12346); // Address of (Graph Plotting) Python server (Change to IP of server)
            OutputStream os = socket.getOutputStream()){
            os.write(message.getBytes());
        } catch(Exception e){
            Log.v("Data Forwarding", e.getMessage());
        }
    }

    public String callLlamaApi(String question, String context) {
        String apiUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash-latest:generateContent?key=AIzaSyArIhyW6Z4PJmeZDXKXvEqe1X-FDAwXNPA";

        try {
            // Create the URL object
            URL url = new URL(apiUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();

            // Set up the connection
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);

            // Create the JSON payload
            String jsonInputString = String.format(
                    "{\"contents\":[{\"parts\":[{\"text\":\"Here is the question :%s And answer based on this context text (you may extend the context if need be): %s. Dont use markdown and don't exceed 30 words.\"}]}]}",
                    question,
                    context
            );

            // Write JSON payload to request body
            try (DataOutputStream wr = new DataOutputStream(connection.getOutputStream())) {
                wr.writeBytes(jsonInputString);
                wr.flush();
            }

            // Get the response code
            int responseCode = connection.getResponseCode();
            System.out.println("Response Code: " + responseCode);

            // Read the response from the API
            StringBuilder response = new StringBuilder();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                String inputLine;
                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
            }

            // Parse the JSON response
            JSONObject jsonResponse = new JSONObject(response.toString());
            // Accessing the required parts from the new JSON structure
            JSONArray candidates = jsonResponse.getJSONArray("candidates");
            String resultText = "";

            if (candidates.length() > 0) {
                // Access the first candidate
                JSONObject candidate = candidates.getJSONObject(0);
                JSONObject content = candidate.getJSONObject("content");
                JSONArray parts = content.getJSONArray("parts");
                if (parts.length() > 0) {
                    // Retrieve the text from the first part
                    resultText = parts.getJSONObject(0).getString("text");
                }
            }

            // Return the extracted text
            return resultText;

        } catch (Exception e) {
            e.printStackTrace();
            return "Error: " + e.getMessage();
        }
    }

    private int getBatteryLevel(Context context) {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = context.registerReceiver(null, ifilter);

        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);

        return (int) ((level / (float) scale) * 100); // Battery level in percentage
    }

    private float getCpuUsage() {
        try {
            String[] args = {"/system/bin/top", "-b", "-n", "1"};
            Process process = Runtime.getRuntime().exec(args);
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                Log.d("/system/bin/top", "" + line);  // Print each line for debugging
                if (line.matches("\\s*\\d+.*\\s+[RS]\\s+\\d+\\.\\d+.*")) {
                    String[] parts = line.trim().split("\\s+");

                    // Ensure the line has enough parts to extract the CPU value
                    if (parts.length > 8) {
                        try {
                            // Extract the CPU percentage from the correct column (usually 9th column)
                            String cpuUsageStr = parts[8];  // Index 8 for the 9th column
                            float cpuUsage = Float.parseFloat(cpuUsageStr);

                            // Return the extracted CPU usage
                            return cpuUsage;
                        } catch (NumberFormatException e) {
                            // Handle invalid number format case
                            e.printStackTrace();
                        }
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return -1f;

    }

    private float getBatteryTemperature() {
        IntentFilter iFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = context.registerReceiver(null, iFilter);
        if (batteryStatus != null) {
            int temperature = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
            return temperature / 10f; // Convert to Celsius
        }
        return -1f;
    }


    private synchronized List<QaAnswer> getBestAnswers(float[] startLogits, float[] endLogits, Feature feature) {
        int[] startIndexes = getBestIndex(startLogits, feature.tokenToOrigMap);
        int[] endIndexes = getBestIndex(endLogits, feature.tokenToOrigMap);
        List<QaAnswer.Pos> origResults = new ArrayList<>();

        for (int start : startIndexes) {
            for (int end : endIndexes) {
                if (end < start) {
                    continue;
                }
                int length = end - start + 1;
                if (length > MAX_ANS_LEN) {
                    continue;
                }
                origResults.add(new QaAnswer.Pos(start, end, startLogits[start] + endLogits[end]));
            }
        }

        Collections.sort(origResults);
        List<QaAnswer> answers = new ArrayList<>();
        for (int i = 0; i < origResults.size(); i++) {
            if (i >= PREDICT_ANS_NUM) {
                break;
            }
            String convertedText = origResults.get(i).start > 0 ? convertBack(feature, origResults.get(i).start, origResults.get(i).end) : "";
            QaAnswer ans = new QaAnswer(convertedText, origResults.get(i));
            answers.add(ans);
        }
        return answers;
    }

    @WorkerThread
    private synchronized int[] getBestIndex(float[] logits, Map<Integer, Integer> tokenToOrigMap) {
        List<QaAnswer.Pos> tmpList = new ArrayList<>();
        for (int i = 0; i < MAX_SEQ_LEN; i++) {
            if (tokenToOrigMap.containsKey(i + OUTPUT_OFFSET)) {
                tmpList.add(new QaAnswer.Pos(i, i, logits[i]));
            }
        }

        Collections.sort(tmpList);
        int[] indexes = new int[PREDICT_ANS_NUM];
        for (int i = 0; i < PREDICT_ANS_NUM; i++) {
            indexes[i] = tmpList.get(i).start;
        }

        return indexes;
    }

    @WorkerThread
    private static String convertBack(Feature feature, int start, int end) {
        int shiftedStart = start + OUTPUT_OFFSET;
        int shiftedEnd = end + OUTPUT_OFFSET;
        int startIndex = feature.tokenToOrigMap.get(shiftedStart);
        int endIndex = feature.tokenToOrigMap.get(shiftedEnd);
        return SPACE_JOINER.join(feature.origTokens.subList(startIndex, endIndex + 1));
    }

    public native String queryRuntimes(String nativeDirPath);
    public native String initSNPE(AssetManager assetManager);
    public native String inferSNPE(String runtime, float[] input_ids, float[] attn_masks, float[] seg_ids, int arraySizes, float[] startLogits, float[] endLogits);
}