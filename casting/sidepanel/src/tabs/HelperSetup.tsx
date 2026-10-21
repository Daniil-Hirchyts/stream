import { Icon } from "../icons";
import { refreshHelper } from "../state";

/** Shown until Chrome can start the desktop helper. */
export function HelperSetup() {
  return (
    <section className="card setup">
      <span className="empty-icon"><Icon name="terminal" /></span>
      <h2>Install the desktop helper</h2>
      <p>Install Daniil Cast on this computer so Chrome can reach your TV. On a Mac, open Daniil Cast.app once to finish setup.</p>
      <button type="button" className="button secondary" onClick={() => void refreshHelper()}>
        <Icon name="refresh" />Check again
      </button>
    </section>
  );
}
