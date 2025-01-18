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

package com.qualcomm.qti.qa.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Debug;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.text.Editable;
import android.text.InputType;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextWatcher;
import android.text.method.ScrollingMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.TextView;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import com.qualcomm.qti.R;
import com.qualcomm.qti.qa.ml.LoadDatasetClient;
import com.qualcomm.qti.qa.ml.QaAnswer;
import com.qualcomm.qti.qa.ml.QaClient;
import java.io.*;
import java.net.*;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Paths;


/** Activity for doing Q&A on a specific dataset */
public class QaActivity extends AppCompatActivity implements QaClient.QaActivityCallback {

    private static final String DATASET_POSITION_KEY = "DATASET_POSITION";
    private static final String TAG = "SNPE_Activity";
    private static final boolean DISPLAY_RUNNING_TIME = true;
    private static boolean editable = false;

    private TextInputEditText questionEditText;
    private TextView contentTextView;
    private TextToSpeech textToSpeech;
    private TextView textViewBatteryLevel;
    private TextView textViewCpuUsage;
    private TextView textViewBatteryConsumption;
    private TextView textViewSelectedModel;
    private TextView textAnswer;

    private boolean questionAnswered = false;
    private String content;
    private Handler handler;
    private QaClient qaClient;
//    private BatteryMonitor batteryMonitor;

    public static Intent newInstance(Context context, int datasetPosition) {
        Intent intent = new Intent(context, QaActivity.class);
        intent.putExtra(DATASET_POSITION_KEY, datasetPosition);
        return intent;
    }

    public static boolean Adapt = true;
    public static int modelToUse = 0;
    public static boolean firstQuestion = true;
    private static double userFeedbackScore = 0.5;
    public static long beforeTime = 0L;
    public static long afterTime= 0L;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.v(TAG, "onCreate");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qa);

        // Initialize views
        initializeViews();


        // Get content of the selected dataset.
        int datasetPosition = getIntent().getIntExtra(DATASET_POSITION_KEY, -1);
        LoadDatasetClient datasetClient = new LoadDatasetClient(this);

        // Show the dataset title.
        TextView titleText = findViewById(R.id.title_text);
        titleText.setText(datasetClient.getTitles()[datasetPosition]);

        Log.d("Cheks", titleText.getText().toString().trim());

        // Show the text content of the selected dataset.
        content = datasetClient.getContent(datasetPosition);
        contentTextView = findViewById(R.id.content_text);
        contentTextView.setText(content);
        contentTextView.setMovementMethod(new ScrollingMovementMethod());
        editable = "New Text".equals(titleText.getText().toString().trim());
        setContentTextEditable();
//         Setup question suggestion list.
        RecyclerView questionSuggestionsView = findViewById(R.id.suggestion_list);
        QuestionAdapter adapter =
                new QuestionAdapter(this, datasetClient.getQuestions(datasetPosition));
        adapter.setOnQuestionSelectListener(question -> answerQuestion(question));
        questionSuggestionsView.setAdapter(adapter);
        LinearLayoutManager layoutManager =
                new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false);
        questionSuggestionsView.setLayoutManager(layoutManager);

        //Setup feedback buttons
        ImageButton thumbsUpButton = findViewById(R.id.thumbs_up_button);
        // Setup ask button.
        ImageButton askButton = findViewById(R.id.ask_button);
        askButton.setOnClickListener(
                view -> answerQuestion(questionEditText.getText().toString()));

        ImageButton thumbsDownButton = findViewById(R.id.thumbs_down_button);

        thumbsUpButton.setOnClickListener( view -> {
                    userFeedbackScore = 1.0;
        });
        thumbsDownButton.setOnClickListener( view -> {
                    userFeedbackScore = 0.0;
        });

        //=========================== Runtime Selection ==============================//
        Spinner dropdown = findViewById(R.id.runtime_spinner);
        String[] items = new String[]{"Adapt", "Electra", "Bert", "Ollama", "Gemini"};

        ArrayAdapter<String> ddadapter = new ArrayAdapter<String>(QaActivity.this,
                android.R.layout.simple_spinner_item, items);

        ddadapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        dropdown.setAdapter(ddadapter);
        dropdown.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> arg0, View arg1,
                                               int arg2, long arg3) {
                        String runtime = dropdown.getSelectedItem().toString();
                        Log.i("SPINNER: Dropdown selected is  ", runtime);
                        if(runtime.equals("Adapt")){
                            Adapt = true;
                        }
                        else{
                            Adapt = false;
                            if(runtime.equals("Electra"))
                                modelToUse = 0;
                            else if(runtime.equals("Bert"))
                                modelToUse = 1;
                            else if(runtime.equals("Ollama"))
                                modelToUse = 2;
                            else
                                modelToUse = 3;
                        }
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> arg0) {
                        // TODO Auto-generated method stub
                        String runtime = "Adapt";
                        Adapt = true;
                    }
                });
        //=========================== Runtime Selection End==============================//

        // Setup text edit where users can input their question.
        questionEditText = findViewById(R.id.question_edit_text);
        questionEditText.setOnFocusChangeListener(
                (view, hasFocus) -> {
                    // If we already answer current question, clear the question so that user can input a new
                    // one.
                    if (hasFocus && questionAnswered) {
                        questionEditText.setText(null);
                    }
                });
        questionEditText.addTextChangedListener(
                new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {
                    }

                    @Override
                    public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {
                        // Only allow clicking Ask button if there is a question.
                        boolean shouldAskButtonActive = !charSequence.toString().isEmpty();
                        askButton.setClickable(shouldAskButtonActive);
                        askButton.setImageResource(
                                shouldAskButtonActive ? R.drawable.ic_ask_active : R.drawable.ic_ask_inactive);
                    }

                    @Override
                    public void afterTextChanged(Editable editable) {
                    }
                });
        questionEditText.setOnKeyListener(
                (v, keyCode, event) -> {
                    if (event.getAction() == KeyEvent.ACTION_UP && keyCode == KeyEvent.KEYCODE_ENTER) {
                        answerQuestion(questionEditText.getText().toString());
                    }
                    return false;
                });

        // Setup QA client to and background thread to run inference.
        HandlerThread handlerThread = new HandlerThread("QAClient");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
        qaClient = new QaClient(this);
        qaClient.setQaActivityCallback(this);
    }

    private void initializeViews() {
        questionEditText = findViewById(R.id.question_edit_text);
        contentTextView = findViewById(R.id.content_text);
        textViewBatteryLevel = findViewById(R.id.textViewBatteryLevel);
        textViewCpuUsage = findViewById(R.id.textViewCpuUsage);
        textViewBatteryConsumption = findViewById(R.id.textViewBatteryConsumption);
        textViewSelectedModel = findViewById(R.id.textViewSelectedModel);
        textAnswer = findViewById(R.id.textAnswer);
    }

    private void setContentTextEditable() {
        contentTextView.setFocusable(editable);
        contentTextView.setFocusableInTouchMode(editable);
        contentTextView.setClickable(editable);
        contentTextView.setLongClickable(editable);
        contentTextView.setCursorVisible(editable);
//        contentTextView.
//        contentTextView.setBackgroundResource(editable ? android.R.drawable.edit_text : android.R.color.transparent);
        contentTextView.setTextIsSelectable(editable);
        if (!editable) {
//          contentTextView.clearFocus();
            Log.d("Edit2", "Disabled editing");
        }
        else {
            contentTextView.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            Log.d("Edit2", "Allowed Editing");
        }
    }

    @Override
    public void onMetricsUpdated(int batteryLevel, float cpuUsage, int modelToUse) {
        runOnUiThread(() -> {
            textViewBatteryLevel.setText("Battery Level: " + batteryLevel + "%");
            textViewCpuUsage.setText("CPU Usage: " + cpuUsage + "%");
            Log.d("display", "usage: " + cpuUsage + " consumption: " + (cpuUsage * batteryLevel / 100.0f));
            textViewBatteryConsumption.setText("Battery Consumption: " + (cpuUsage * batteryLevel / 100.0f) + "%");
            textViewSelectedModel.setText("Model: " + (modelToUse == -1 ? "Cache" : (modelToUse == 0 ? "Electra (Local)" : (modelToUse == 1 ? "Bert(Local)" : (modelToUse == 2 ? "Ollama (Cloud)" : "Gemini (Cloud)")))));
        });
    }

    @Override
    protected void onStart() {
        Log.v(TAG, "onStart");
        super.onStart();
        handler.post(
                () -> {
                    String initLogs = qaClient.loadModel();
                    if (!initLogs.isEmpty()) {
                        Snackbar initSnackbar =
                                Snackbar.make(contentTextView, initLogs, Snackbar.LENGTH_SHORT);
                        initSnackbar.show();
                    }
                    qaClient.loadDictionary();
                });

        textToSpeech =
                new TextToSpeech(
                        this,
                        status -> {
                            if (status == TextToSpeech.SUCCESS) {
                                textToSpeech.setLanguage(Locale.US);
                            } else {
                                textToSpeech = null;
                            }
                        });
         
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

    private int getBatteryLevel(Context context) {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = context.registerReceiver(null, ifilter);

        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);

        return (int) ((level / (float) scale) * 100); // Battery level in percentage
    }

    @Override
    protected void onStop() {
        Log.v(TAG, "onStop");
        super.onStop();
        handler.post(() -> qaClient.unload());

        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
    }

    private void answerQuestion(String question) {
        question = question.trim();
        if (question.isEmpty()) {
            questionEditText.setText(question);
            return;
        }

        // Append question mark '?' if not ended with '?'.
        // This aligns with question format that trains the model.
        if (!question.endsWith("?")) {
            question += '?';
        }
        final String questionToAsk = question;
        questionEditText.setText(questionToAsk);

        // Delete all pending tasks.
        handler.removeCallbacksAndMessages(null);

        // Hide keyboard and dismiss focus on text edit.
        InputMethodManager imm =
                (InputMethodManager) getSystemService(AppCompatActivity.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(), 0);
        View focusView = getCurrentFocus();
        if (focusView != null) {
            focusView.clearFocus();
        }

        // Reset content text view
//        if(!editable)contentTextView.setText(content);

        content = contentTextView.getText().toString();

        questionAnswered = false;

        Snackbar runningSnackbar =
                Snackbar.make(contentTextView, "Looking up answer...", Snackbar.LENGTH_INDEFINITE);
        runningSnackbar.show();

        // Run TF Lite model to get the answer.
        handler.post(
                () -> {
                    Spinner dropdown = findViewById(R.id.runtime_spinner);
                    String runtime = dropdown.getSelectedItem().toString();
                    Log.i("SPINNER: Dropdown selected is  ", runtime);

                    StringBuilder execStatus = new StringBuilder();

                    //long beforeTime = System.currentTimeMillis();
                    // final String answers = qaClient.predict(questionToAsk, content, runtime, execStatus);
                    final String answer = qaClient.getAnswer(questionToAsk, content, userFeedbackScore, firstQuestion);
                    //long afterTime = System.currentTimeMillis();
                    double totalSeconds = (afterTime - beforeTime) / 1000.0;
                    userFeedbackScore = 0.5;
                    firstQuestion = false;

                    // if (!answers.isEmpty()) {
                        // Get the top answer
                        QaAnswer topAnswer = new QaAnswer(answer, new QaAnswer.Pos(0, 0, 0.0f));
                        // Show the answer.

                        runOnUiThread(
                                () -> {
                                    textAnswer.setText("Answer: " + answer);
                                    runningSnackbar.dismiss();
                                    presentAnswer(topAnswer);

                                    String displayMessage = (QaClient.displayCache ? "Cache" : runtime) + " runtime took : ";
                                    QaClient.displayCache = false;
                                    if (DISPLAY_RUNNING_TIME) {
                                        displayMessage = String.format("%s %.3f sec.", displayMessage, totalSeconds);
                                    }
                                    if (!execStatus.toString().isEmpty())
                                        Snackbar.make(contentTextView, execStatus.toString(), Snackbar.LENGTH_LONG).show();
                                    else
                                        Snackbar.make(contentTextView, displayMessage, Snackbar.LENGTH_LONG).show();

                                    questionAnswered = true;
                                });
                    });
    }


    private void presentAnswer(QaAnswer answer) {
        // Highlight answer.
        Spannable spanText = new SpannableString(content);
        int offset = content.indexOf(answer.text, 0);
        if (offset >= 0) {
            spanText.setSpan(
                    new BackgroundColorSpan(getColor(R.color.secondaryColor)),
                    offset,
                    offset + answer.text.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        contentTextView.setText(spanText);

        // Use TTS to speak out the answer.
        if (textToSpeech != null) {
            textToSpeech.speak(answer.text, TextToSpeech.QUEUE_FLUSH, null, answer.text);
        }
    }
}

