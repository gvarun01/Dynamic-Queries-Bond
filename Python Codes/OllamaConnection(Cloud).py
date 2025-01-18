import socket
import subprocess
import json
# import pyRAPL

# pyRAPL.setup()

def call_curl(prompt):
    curl_command = [
        "curl", "http://localhost:11434/api/generate",
        "-d", json.dumps({
            "model": "gemma2:2b",
            # "model": "llama3.2",
            "prompt": prompt,
            "stream": False
        })
    ]
    
    try:
        result = subprocess.run(curl_command, capture_output=True, text=True, check=True)
        response_json = json.loads(result.stdout)

        if(response_json):
            return response_json["response"]
        else:
            print("No response received from the model server")
            return None

    except subprocess.CalledProcessError as e:
        print(f"Error calling curl: {e}")
        return None


def handle_connection(client_socket):
    try:
        data = client_socket.recv(1024).decode('utf-8')
        print(f"Received from Java: {data}")

        # meter = pyRAPL.Measurement("curl_measurement")
        # meter.begin() 
        response = call_curl(data)
        # meter.end()

        # print("\nPower Usage Report:")
        # print(meter.result)

        client_socket.send(response.encode('utf-8'))

        print("Answer : " + response)
    finally:
        client_socket.close()

def start_server():
    server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server_socket.bind(('0.0.0.0', 12345)) 
    server_socket.listen(1)
    print("Server listening on port 12345...")

    while True:
        client_socket, client_address = server_socket.accept()
        print(f"Connection from {client_address}")
        handle_connection(client_socket)

if __name__ == "__main__":
    start_server()
