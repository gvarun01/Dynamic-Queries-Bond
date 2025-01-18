package com.qualcomm.qti.qa.ml;

import java.util.LinkedList;
import java.util.Queue;


public class MLFQ {

    private static final int PRIORITY_HIGH = 0;
    private static final int PRIORITY_MID = 1;
    private static final int PRIORITY_LOW = 2;

    private static final int ElectraNo = 0;
    private static final int BertNo = 1;
    private static final int OllamaNo = 2;
    private static final int GeminiNo = 3;

    public class Model {
        int number;
        String name;
        String type;
        int priority;
        int executionCount = 0;
        float averageTime = 0;
        float eFactor;
        float tokenFactor;
        int timesRejected = 0;

        Model(int number, String name, String type, int priority, float eFactor, float tokenFactor) {
            this.number = number;
            this.name = name;
            this.type = type;
            this.priority = priority;
            this.eFactor = eFactor;
            this.tokenFactor = tokenFactor;
        }

        void updateTime(float time) {
            averageTime = (averageTime * (executionCount - 1) + time) / executionCount;
        }
    }

    public Queue<Model> highPriorityQueue;
    public Queue<Model> midPriorityQueue;
    public Queue<Model> lowPriorityQueue;

    public double emaScore;

    private MLFQ(){
        highPriorityQueue = new LinkedList<>();
        midPriorityQueue = new LinkedList<>();
        lowPriorityQueue = new LinkedList<>();

        midPriorityQueue.add(new Model(ElectraNo, "Electra", "Local", PRIORITY_MID, 1.5f, 1.0f));
        midPriorityQueue.add(new Model(BertNo, "Bert", "Local", PRIORITY_MID, 1.5f, 1.0f));
        midPriorityQueue.add(new Model(OllamaNo, "Ollama", "Cloud", PRIORITY_MID, 1.0f, 1.0f));
        midPriorityQueue.add(new Model(GeminiNo, "Gemini", "Cloud", PRIORITY_MID, 0.75f, 1.0f));

        emaScore = 0.5;
    }
    private static MLFQ instance;

    public static synchronized MLFQ getInstance() {
        if(instance == null){
            instance = new MLFQ();
        }
        return instance;
    }
}
