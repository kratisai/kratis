from flask import Flask
import requests
from models import UserService

app = Flask(__name__)

@app.route("/api/users/list")
def get_users():
    response = requests.get("https://api.example.com/users")
    return response.json()

class UserController:
    def __init__(self):
        pass
