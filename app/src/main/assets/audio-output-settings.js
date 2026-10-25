import { request, listen } from "./bridge.js";

let current;
listen("audioOutputs", data => current?.update(data));

export function mountAudioOutputSettings(parent, { native, active }) {
  const root = document.createElement("section"); root.className = "audioOutputSettings";
  const label = document.createElement("label"); label.textContent = "Audio output / USB DAC";
  const select = document.createElement("select"); select.id = "audio-output-select"; label.htmlFor = select.id;
  const refresh = document.createElement("button"); refresh.type = "button"; refresh.textContent = "Refresh devices";
  const hint = document.createElement("p"); hint.className = "hint"; hint.textContent = "Choose a DAC connected to this phone.";
  const status = document.createElement("p"); status.className = "hint"; status.setAttribute("role", "status");
  root.append(label, select, refresh, hint, status); parent.append(root);
  let busy = false, latest = { devices: [], selected: "", unavailable: false };
  const update = data => {
    if (!active() || !root.isConnected) return;
    latest = data;
    select.replaceChildren(new Option("System default", ""));
    for (const device of data.devices || []) select.add(new Option(device.label, device.id));
    select.value = data.selected || "";
    select.disabled = refresh.disabled = !native || busy;
    status.textContent = !native ? "Output selection is available in the Android app."
      : data.unavailable ? data.preferredLabel + " is disconnected. Using system default; reconnect it to restore your choice."
      : data.selected ? "Preferred output: " + (data.devices.find(d => d.id === data.selected)?.label || "Audio device")
      : "Using system default.";
  };
  const run = async (action, data = {}) => {
    busy = true; update(latest);
    try { const result = await request(action, data); busy = false; update(result); }
    catch (e) { busy = false; update(latest); if (active()) status.textContent = e.message; }
  };
  select.onchange = () => run("audioOutputSelect", { device: select.value });
  refresh.onclick = () => run("audioOutputs");
  current = { update }; update(latest);
  if (native) void run("audioOutputs");
}
