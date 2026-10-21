import { useEffect } from "react";
import { Icon, type IconName } from "./icons";
import { activity, helper, page, pollTv, refreshHelper, scanActiveTab, scanSoon, showTab, ui, type TabName } from "./state";
import { useStore } from "./store";
import { DeveloperTab } from "./tabs/DeveloperTab";
import { CastTab } from "./tabs/CastTab";
import { RemoteTab } from "./tabs/RemoteTab";
import { TvTab } from "./tabs/TvTab";

const TABS: { name: TabName; label: string; icon: IconName }[] = [
  { name: "cast", label: "Cast", icon: "cast" },
  { name: "remote", label: "Remote", icon: "remote" },
  { name: "tv", label: "TV", icon: "tv" },
  { name: "developer", label: "Developer", icon: "terminal" }
];

export function App() {
  const { tab } = useStore(ui);
  const unseen = useStore(activity).unseen;
  useEffect(start, []);
  return (
    <div className="app">
      <nav className="tabs" role="tablist">
        {TABS.map(item => (
          <button key={item.name} type="button" role="tab" aria-selected={tab === item.name} aria-label={item.label}
            onClick={() => showTab(item.name)}>
            <Icon name={item.icon} />
            <span>{item.label}</span>
            {item.name === "developer" && unseen && <i className="dot" aria-label="New problems" />}
          </button>
        ))}
      </nav>
      <main>
        {tab === "cast" && <CastTab />}
        {tab === "remote" && <RemoteTab />}
        {tab === "tv" && <TvTab />}
        {tab === "developer" && <DeveloperTab />}
      </main>
    </div>
  );
}

function start() {
  void refreshHelper().then(() => pollTv());
  void scanActiveTab();
  // Status is read every second while the panel is visible: the TV answers on the local network in milliseconds.
  const timer = setInterval(() => {
    if (document.visibilityState !== "visible") return;
    if (helper.get().state !== "ready") void refreshHelper();
    else void pollTv();
  }, 1000);
  const activated = () => scanSoon(100);
  const updated = (tabId: number, change: chrome.tabs.TabChangeInfo) => {
    if (tabId === page.get().tabId && (change.status === "complete" || change.url)) scanSoon();
  };
  const detected = (message: { type?: string; tabId?: number }) => {
    if (message?.type === "detected" && message.tabId === page.get().tabId) scanSoon(900);
  };
  chrome.tabs.onActivated.addListener(activated);
  chrome.tabs.onUpdated.addListener(updated);
  chrome.windows.onFocusChanged.addListener(activated);
  chrome.runtime.onMessage.addListener(detected);
  return () => {
    clearInterval(timer);
    chrome.tabs.onActivated.removeListener(activated);
    chrome.tabs.onUpdated.removeListener(updated);
    chrome.windows.onFocusChanged.removeListener(activated);
    chrome.runtime.onMessage.removeListener(detected);
  };
}
