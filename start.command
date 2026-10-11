#!/bin/bash
# Double-click this file to start the video server. Close the window (or press Ctrl+C) to stop it.
cd "$(dirname "$0")"
exec python3 server.py
