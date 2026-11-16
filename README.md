# Garupa Export

A tool for iOS and Android that converts account data from the Chinese server of BanG Dream! Girls Band Party! into a [Bestdori](https://bestdori.com) profile for team analysis and score optimization.

The app runs a local proxy on your phone and uses MITM to capture the game's account data response. It decrypts and parses cards, area items, and character potentials, then generates a JSON profile that can be imported into Bestdori.

## Workflow

1. Start a capture session in the app and follow the prompts to complete the required certificate and proxy setup.
2. Leave the app running in the background, reopen the game, and log in.
3. The app automatically captures the account data response and converts it into a Bestdori profile.
4. Copy, save, or share the generated JSON file and import it into Bestdori for team analysis.
5. Follow the app's prompts to disable the system proxy and stop the capture service.
