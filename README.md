# Intelligent Model Switching System (ESW Project)
### Project title: Dynamic Queries: Adaptive QnA across edge and cloud.

This repository is a fork of Qualcomm's AI Hub, modified by the **BondBrothers** team to create an **Intelligent Model Switching System** for real-time Question Answering (QA). Our project optimizes model selection by dynamically switching between cloud and edge models, leveraging environmental and performance metrics.

## Modified Files

The following files have been created or modified for this project:

### Java Source Code
1. **`src/main/java/com/qualcomm/qti/qa/ml/`**
   - `QaClient.java`: Implements MLFQ-inspired scheduling logic and manages model inference.
   - `MLFQ.java`: Manages model queues and dynamically adjusts model priorities.
   - `QaAnswerCache.java`: Implements a caching mechanism for previously computed QA answers.
   - `BertQaHelper.java` : Load Bert(Local) model (`mobilebert.tflite`) and contains the prediction function.

2. **`src/main/java/com/qualcomm/qti/qa/ui/`**
   - `QaActivity.java`: Defines the primary activity for user interaction, including runtime selection and feedback integration.

3. **`src/main/java/com/qualcomm/qti/qa/tokenization/`**
   - Contains all files that help tokenize the input for local models.

### Layout
4. **`src/res/layout/`**
   - `activity_qa.xml`: Defines the user interface for the QA activity.

---

## Features

### Core Functionality
- **Dynamic Model Switching**: Utilizes edge and cloud-based QA models (Electra, Bert, Gemini, Ollama).
- **Multi-Level Feedback Queue (MLFQ)**: Prioritizes models dynamically based on query characteristics and performance metrics.
- **Caching**: Reduces redundant computations by storing and retrieving results for frequently asked questions.

### User Interaction
- **Adapt/Manual Modes**: Automatically select models or allow manual model selection.
- **Feedback Integration**: Incorporates user feedback into model prioritization.
- **Real-Time Metrics Display**: Shows CPU usage, battery level, and runtime model information.

---

## How to Run

1. **Prerequisites**:  
   - Android Studio  
   - Qualcomm Innovation Development Kit (QIDK)

2. **Clone the Repository**:  
   - git clone [https://github.com/SaiyamJain20/ESW](https://github.com/SaiyamJain20/ESW)

3. **Download models and load them**
   - Download Models from [Models Drive Link](https://iiitaphyd-my.sharepoint.com/:f:/g/personal/saiyam_jain_students_iiit_ac_in/EiHADD9kHCVHplfzVl73bLMB73iyVxbgCfj_Rn3EU4f4DA?e=70Giak)
   - Load models in ./bert/src/main/assets/

3. **Open in Android Studio**:  
   - Import the project into Android Studio.  
   - Sync Gradle to fetch dependencies.

4. **Build and Deploy**:  
   - Connect the QIDK.  
   - Build and run the app using the **Run** button in Android Studio.  

---

## Individual Contributions

- **Krishak Aneja**: Designed the MLFQ scheduling and scoring mechanism; contributed to UI development.  
- **Saiyam Jain**: Managed edge model lifecycle, tokenization, and caching logic.  
- **Varun Gupta**: Integrated cloud-based functionalities and optimized API interactions.  
- **Gracy Garg**: Analyzed system performance and created graphs for evaluation.

---

## Acknowledgments

This project is supported by **Qualcomm AI Hub** and developed under the guidance of Professor Karthik Vaidhyanathan. Special thanks to the Qualcomm team for providing resources and insights.

---

## Link to Models Used
Here is the [Models Drive Link](https://iiitaphyd-my.sharepoint.com/:f:/g/personal/saiyam_jain_students_iiit_ac_in/EiHADD9kHCVHplfzVl73bLMB73iyVxbgCfj_Rn3EU4f4DA?e=70Giak) to the models used in this project.


## Ollama Documentation
[Ollama](https://github.com/ollama/ollama?tab=readme-ov-file)