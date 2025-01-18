package com.qualcomm.qti.qa.ml;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import org.tensorflow.lite.task.core.BaseOptions;
import org.tensorflow.lite.task.text.qa.BertQuestionAnswerer;
import org.tensorflow.lite.task.text.qa.BertQuestionAnswerer.BertQuestionAnswererOptions;
import org.tensorflow.lite.task.text.qa.QaAnswer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class BertQaHelper {

    private static final String BERT_QA_MODEL = "mobilebert.tflite";
    private static final String TAG = "BertQaHelper";

    public static final int DELEGATE_CPU = 0;

    private BertQuestionAnswerer bertQuestionAnswerer;
    private final Context context;
    private int numThreads;
    private final AnswererListener answererListener;



    public BertQaHelper(Context context, int numThreads, int currentDelegate, AnswererListener answererListener) {
        this.context = context;
        this.numThreads = numThreads;
        this.answererListener = answererListener;
        setupBertQuestionAnswerer();
    }

    public void clearBertQuestionAnswerer() {
        bertQuestionAnswerer = null;
    }

    private void setupBertQuestionAnswerer() {
        try {
            // Configure BaseOptions to use NNAPI (NPU)
            BaseOptions.Builder baseOptionsBuilder = BaseOptions.builder()
                    .setNumThreads(numThreads) // Set the number of threads for fallback CPU execution
                    .useNnapi(); // Enable NNAPI for hardware acceleration, including NPU if available.

            // Build the BertQuestionAnswererOptions with the modified BaseOptions
            BertQuestionAnswererOptions options = BertQuestionAnswererOptions.builder()
                    .setBaseOptions(baseOptionsBuilder.build())
                    .build();

            // Initialize the BertQuestionAnswerer
            bertQuestionAnswerer = BertQuestionAnswerer.createFromFileAndOptions(context, BERT_QA_MODEL, options);

        } catch (IOException | IllegalStateException e) {
            if (answererListener != null) {
                answererListener.onError("Bert Question Answerer failed to initialize. See error logs for details");
            }
            Log.e(TAG, "TFLite failed to load model with error: " + e.getMessage());
        }
    }

    public void answer(String contextOfQuestion, String question) {
        if (bertQuestionAnswerer == null) {
            setupBertQuestionAnswerer();
        }

        // Inference time is the difference between the system time at the start and finish of the process
        long inferenceTime = SystemClock.uptimeMillis();

        List<QaAnswer> answers = bertQuestionAnswerer.answer(contextOfQuestion, question);
        inferenceTime = SystemClock.uptimeMillis() - inferenceTime;

        if (answererListener != null) {
            answererListener.onResults(answers, inferenceTime);
        }
    }

    public interface AnswererListener {
        void onError(String error);
        void onResults(List<QaAnswer> results, long inferenceTime);  // Correct signature
    }

    public static List<QaAnswer> predictWithBert(Context context, String question, String content, StringBuilder execStatus) {
        final List<QaAnswer> results = new ArrayList<>();
        final boolean[] isCompleted = {false};  // To track if prediction is completed

        // Create a handler to update results when the computation is done
        BertQaHelper bertQaHelper = new BertQaHelper(context, 2, BertQaHelper.DELEGATE_CPU, new BertQaHelper.AnswererListener() {
            @Override
            public void onError(String error) {
                Log.e("BertQaHelper", "Error: " + error);
                execStatus.append("Error: ").append(error);
                isCompleted[0] = true;  // Mark as completed in case of error
            }

            @Override
            public void onResults(List<org.tensorflow.lite.task.text.qa.QaAnswer> resultList, long inferenceTime) {
                for (org.tensorflow.lite.task.text.qa.QaAnswer answer : resultList) {
                    results.add(answer);
                }
                execStatus.append("Inference time: ").append(inferenceTime).append(" ms");
                isCompleted[0] = true;  // Mark as completed when results are available
            }
        });

        // Call Bert to get the answer for the question
        bertQaHelper.answer(content, question);

        // Block the thread until the result is available or an error occurs
        while (!isCompleted[0]) {
            try {
                Thread.sleep(100);  // Sleep for a short time to avoid busy-waiting
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.e("BertQaHelper", "Thread interrupted", e);
            }
        }

        return results;  // Return the results
    }
}
