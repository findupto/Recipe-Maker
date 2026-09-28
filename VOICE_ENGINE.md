# Voice Engine

## Reliability

The Android voice assistant uses the native SpeechRecognizer bridge instead of relying on WebView speech APIs.

### Recovery features
- Recreates the Android recognizer after every result/error cycle.
- Automatically retries after speech timeout, no-match, busy, network and client/service errors.
- Uses Android's offline-preference hint when supported by the installed speech service.
- Rotates between English (US), English (Pakistan), and Urdu (Pakistan) when a language is unavailable.
- Reconnects the voice engine when the app returns to the foreground.
- Stops all retry callbacks immediately when the user turns Voice Assistant off.
- Reports permission/service failures back to the React UI instead of leaving the interface stuck on Listening.

## Supported command styles

The React command layer accepts English, Roman Urdu and common Urdu phrases for:
- adding menu items and quantities
- quick-sale items with prices
- completing/checking out a sale
- printing a receipt
- opening Menu, History, Insights or Settings
- selecting payment methods
- applying discounts
- clearing the current order
- assigning a customer name

## Android requirements

The app requires microphone permission and an installed Android speech-recognition service. Recognition quality and offline availability depend on the speech service installed on the device.

If recognition is unavailable, open Android speech/language settings, enable a speech service (such as Google Speech Services), download the required language pack for offline recognition, then reopen Voice Assistant.
