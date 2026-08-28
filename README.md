# opencompanion

A voice desk companion that runs on spare hardware.

It can:

- talk, with a wake word and a voice
- express emotions with a face
- look through the camera
- remember things
- search the web
- check the weather
- tell the time and set timers
- send notifications to your laptop

The default companion is named Soc.

## How it works

- The opencompanion daemon runs under Termux on a rooted Android phone
- The face is a web page shown full screen in the phone's browser
- Wake-word and voice-activity detection run on the phone
- Speech-to-text, the language model and text-to-speech use an
  OpenAI-compatible API
- Memory is markdown files on the phone

## Requirements

- A rooted arm64 Android phone with Termux and Termux:API
- An OpenAI-compatible API with a chat model (tool calling, and image input
  for the camera), a transcription model and a speech model; an Exa key for
  web search is optional
- A computer with Python 3.12+, rsync and ssh

## Install

On the phone, in Termux:

```
pkg install openssh rsync
sshd
```

Add your computer's public key to `~/.ssh/authorized_keys` on the phone.
Termux's sshd listens on port 8022.

On the computer:

```
git clone https://github.com/vynride/opencompanion
cd opencompanion
OC_HOST=<user@phone> ./scripts/sync.sh
```

On the phone, in `~/opencompanion`:

```
cp config.example.yaml config.yaml
cp .env.example .env
bash scripts/install-phone.sh
python -m opencompanion.main
```

To start on boot, copy `scripts/opencompanion-service.sh` to
`/data/adb/service.d/` as root and make it executable.

`scripts/smoke.sh` checks the microphone, the face and the Python setup while
the daemon is running.

## Configuration

- `config.yaml` for configuration; `config.example.yaml` lists every setting
- `.env` for API keys
- `memory/personality.md` for the companion's tone and personality

## Development

```
python -m venv .venv
.venv/bin/pip install -r requirements.txt -r requirements-dev.txt
.venv/bin/pytest -q
.venv/bin/ruff check opencompanion tests && .venv/bin/ruff format opencompanion tests
```

`python -m opencompanion.face_server --demo` shows the face in a desktop
browser.

