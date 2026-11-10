# Set up phone control

[简体中文](setup-guide.md) | English

X Assistant uses apps on a background screen so your own screen stays free. Android only starts that screen through Wireless debugging, so turning on accessibility is not enough. Developer options and a one-time pairing stay required. Everything happens on the phone. No computer and no cable. The first time takes about 10 minutes. Web, files and chat work without this.

## Where to open it

- The first time you open the app, this step comes after the model.
- If you tapped Not now, open it again from the status card on Settings, or from Settings › Phone control.
- When a task needs the phone and it is not ready, a banner appears at the top of the chat.

The guide is a numbered list. Finished steps get a check. If the phone reports a switch wrong, tap “I’ve turned it on, next”. You can tap a finished step to redo it. Until it succeeds, Not now is always available.

## Step 1: Developer options

Tap Open About phone. In Settings › About phone, tap Build number 7 times until you see that you are a developer. On some phones Build number is under Software information. You can also search Settings for Build number.

## Step 2: USB debugging

Tap Open Developer options, turn on USB debugging, and confirm. No cable is needed. With it on, phone control can keep working when Wi-Fi drops. Leave it on; turning it off stops phone control.

## Step 3: Accessibility (the main step you do yourself)

Tap Open Accessibility settings, find X Assistant, and turn it on. It can then read the pairing code itself.

If the switch is grey or Android says restricted setting:

1. Tap the button that opens App info, or open Settings › Apps › X Assistant yourself.
2. Tap ⋮ at the top right and choose Allow restricted settings. That item sometimes appears only after the first block.
3. Go back to Accessibility and turn X Assistant on.

You can tap “Skip for now and type the code myself”. Skipping only changes how this pairing code is entered. It does not replace accessibility.

## Step 4: Wireless debugging

Join any Wi-Fi. Internet access is not required. In Developer options, turn on Wireless debugging. When asked, tick Always allow on this network, then Allow.

## Step 5: Pair with a code

Tap Open Wireless debugging, then Pair device with pairing code. A 6-digit code appears.

- If accessibility is on, stay on that page for a few seconds. X Assistant reads the code.
- Otherwise a notification pops up. If you do not see it, swipe down the notification shade and tap Enter code.
- The code expires in about a minute. If it is wrong or expired, tap Pair device with pairing code again for a new one.
- Do not leave the pairing window while you type, or it closes.
- If the notification is awkward, expand the field in the guide and type the code there.

## Step 6: Connect

After pairing, X Assistant connects by itself. If it cannot, turn Wireless debugging off and on, or enter the port shown on the Wireless debugging page.

After a restart, turn Wireless debugging on again and X Assistant reconnects. You do not pair again.

## Skip phone control for now

Tap Not now. Chat, web and files still work. The app reminds you the first time a task needs an app.
