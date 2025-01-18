import socket
import matplotlib.pyplot as plt
from datetime import datetime
from matplotlib import colormaps
import csv
import os
from collections import Counter

# CSV file path
CSV_FILE = "data_store.csv"

# Data storage for plotting
data_store = {
    "cpu_usage": [],
    "temperature": [],
    "battery_level": [],
    "model_running": [],
    "model_type": [],
    "user_feedback": [],
    "model_score": [],
    "elapsed_seconds": []
}

def load_csv_data():
    """
    Load data from the CSV file into the data_store dictionary.
    """
    if os.path.exists(CSV_FILE):
        with open(CSV_FILE, mode='r') as file:
            reader = csv.DictReader(file)
            for row in reader:
                data_store["cpu_usage"].append(float(row["cpu_usage"]))
                data_store["temperature"].append(float(row["temperature"]))
                data_store["battery_level"].append(float(row["battery_level"]))
                data_store["model_running"].append(row["model_running"])
                data_store["model_type"].append(row["model_type"])
                data_store["user_feedback"].append(row["user_feedback"])
                data_store["model_score"].append(float(row["model_score"]))
                data_store["elapsed_seconds"].append(float(row["elapsed_seconds"]))

def append_to_csv(cpu_usage, temperature, battery_level, model_running, model_type, user_feedback, model_score, elapsed_seconds):
    """
    Append a single row of data to the CSV file.
    """
    with open(CSV_FILE, mode='a', newline='') as file:
        writer = csv.writer(file)
        writer.writerow([cpu_usage, temperature, battery_level, model_running, model_type, user_feedback, model_score, elapsed_seconds])

def save_csv_headers():
    """
    Save headers to the CSV file if the file doesn't exist.
    """
    if not os.path.exists(CSV_FILE):
        with open(CSV_FILE, mode='w', newline='') as file:
            writer = csv.writer(file)
            writer.writerow(["cpu_usage", "temperature", "battery_level", "model_running", "model_type", "user_feedback", "model_score", "elapsed_seconds"])


def plot_graphs():
    """
    Generate and save graphs for CPU, Battery, and Temperature against time.
    Points are uniquely colored based on the model running.
    """
    # timestamps = data_store["timestamps"]
    cpu_usage = data_store["cpu_usage"]
    temperature = data_store["temperature"]
    battery_level = data_store["battery_level"]
    model_running = data_store["model_running"]
    model_type = data_store["model_type"]
    user_feedback = data_store["user_feedback"]
    model_score = data_store["model_score"]
    elapsed_seconds = data_store["elapsed_seconds"]

    # Unique models for color coding
    unique_models = list(set(model_running))
    colors = colormaps.get_cmap("tab10")
    color_list = [colors(i / len(unique_models)) for i in range(len(unique_models))]

    plt.ion()

    def plot_individual_graph(y_values, title, ylabel, filename):
        """Helper function to plot individual graphs."""
        plt.figure(figsize=(10, 6))
        plt.clf()
        for i, state in enumerate(unique_models):
            state_indices = [idx for idx, s in enumerate(model_running) if s == state]
            state_seconds = [elapsed_seconds[idx] for idx in state_indices]
            state_values = [y_values[idx] for idx in state_indices]
            color = color_list[i]

            plt.scatter(state_seconds, state_values, label=f"Running: {state}", color=color)
            plt.plot(state_seconds, state_values, color=color, linestyle='-', alpha=0.7)

        plt.title(title)
        plt.xlabel("Elapsed Time (s)")
        plt.ylabel(ylabel)
        plt.legend(title="Model Running")
        plt.grid()
        plt.savefig(filename)
        plt.draw()
        # plt.show(block=False)
        plt.pause(0.1)
        plt.close()

    # Plot CPU Usage vs Time
    plot_individual_graph(cpu_usage, "CPU Usage vs Time", "CPU Usage (%)", "cpu_usage_vs_time.png")

    plot_individual_graph(model_score, "Model Score vs Time","Score","score_vs_time.png")

    def create_pie(data, title, filename, bins=None):
        """Helper function to create pie charts."""
        if bins:
            # For numeric data (e.g., battery or CPU usage), bin the values
            labels = [f"{bins[i]}-{bins[i+1]}" for i in range(len(bins)-1)]
            binned_data = [sum(bins[i] <= val < bins[i+1] for val in data) for i in range(len(bins)-1)]
            counts = dict(zip(labels, binned_data))
        else:
            # For categorical data, count occurrences
            counts = dict(Counter(data))

        labels = list(counts.keys())
        sizes = list(counts.values())

        # Plot pie chart
        plt.figure(figsize=(6, 6))
        plt.pie(sizes, labels=labels, autopct='%1.1f%%', startangle=140, colors=plt.cm.tab10.colors[:len(labels)])
        plt.title(title)
        plt.savefig(filename)
        plt.draw()  # Render the figure
        # plt.show(block=False)  # Show non-blocking
        plt.pause(0.1)  # Pause for 2 seconds (adjust time as needed)
        plt.close()  # Close the plot
    
    create_pie(model_running, "Model Running Status", "model_running_pie.png")

    create_pie(model_type, "Model Type", "model_type.png")

    create_pie(user_feedback, "User Feedback", "User Feedback")


# Initialize CSV headers and load existing data
save_csv_headers()
load_csv_data()

# Socket server setup
server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
server.bind(('0.0.0.0', 12346))
server.listen(1)
print("Server listening on port 12346...")
start_time = None

while True:
    client, addr = server.accept()
    print(f"Connected by {addr}")
    data = client.recv(1024).decode()

    # Parse and extract values
    parts = data.split()
    try:
        cpu_usage = float(parts[0])
        temperature = float(parts[1])
        battery_level = float(parts[2])
        model_running = parts[3]
        model_type = parts[4]
        user_feedback = float(parts[5])
        model_score = float(parts[6])
    except (ValueError, IndexError) as e:
        print(f"Error parsing data: {e}")
        client.close()
        continue

    # Generate current timestamp on the server
    current_time = datetime.now()
    # start_time = 0
    if not start_time:
        start_time = current_time  # Initialize start time
    elapsed_seconds = (current_time - start_time).total_seconds()
    # timestamp = current_time.strftime('%H:%M:%S')

    # Store data
    # data_store["timestamps"].append(timestamp)
    data_store["cpu_usage"].append(cpu_usage)
    data_store["temperature"].append(temperature)
    data_store["battery_level"].append(battery_level)
    data_store["model_running"].append(model_running)
    data_store["model_type"].append(model_type)
    data_store["user_feedback"].append(user_feedback)
    data_store["model_score"].append(model_score)
    data_store["elapsed_seconds"].append(elapsed_seconds)

    # Append data to CSV
    append_to_csv(cpu_usage, temperature, battery_level, model_running, model_type, user_feedback, model_score, elapsed_seconds)

    # Print received data
    print(f"CPU Usage: {cpu_usage}")
    print(f"Temperature: {temperature}")
    print(f"Battery Level: {battery_level}")
    print(f"Model Running: {model_running}")
    # print(f"Timestamp: {timestamp}")

    # Generate and save plots
    plot_graphs()
    client.close()
