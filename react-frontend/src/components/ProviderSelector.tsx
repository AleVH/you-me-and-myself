/**
 * Provider selector component.
 *
 * ## What It Shows
 *
 * A dropdown of configured AI providers (populated from PROVIDERS_LIST event).
 *
 * ## R4 Change
 *
 * The "New Chat" button has been moved to the TabBar component where it
 * now creates a new tab instead of just clearing the display. This component
 * is now solely responsible for the provider dropdown.
 *
 * ## How It Works
 *
 * On mount, the useBridge hook sends REQUEST_PROVIDERS. The backend responds
 * with PROVIDERS_LIST containing all configured chat-capable profiles and
 * the currently selected one. This component renders them as a `<select>`.
 *
 * When the user picks a different provider, it dispatches SWITCH_PROVIDER
 * to the backend, which updates AiProfilesState. The next SEND_MESSAGE
 * will use the new provider.
 *
 * ## Empty State
 *
 * If no providers are configured (empty list), shows a placeholder message
 * directing the user to Settings. This is common on first install.
 *
 * @see BridgeMessage.RequestProviders — command sent on mount
 * @see BridgeMessage.ProvidersListEvent — response with provider list
 * @see BridgeMessage.SwitchProvider — command sent on selection change
 */
import type { ProviderInfoDto } from "../bridge/types";

interface ProviderSelectorProps {
    providers: ProviderInfoDto[];
    selectedId: string | null;
    onSelect: (providerId: string) => void;
}

function ProviderSelector({
                              providers,
                              selectedId,
                              onSelect,
                          }: ProviderSelectorProps) {
    return (
        <div className="ymm-provider-bar">
            {providers.length > 0 ? (
                <select
                    className="ymm-provider-bar__select"
                    value={selectedId ?? ""}
                    onChange={(e) => onSelect(e.target.value)}
                >
                    {providers.map((p) => (
                        <option key={p.id} value={p.id}>
                            {p.label}
                        </option>
                    ))}
                </select>
            ) : (
                <span className="ymm-provider-bar__empty">
          No providers configured — check Settings
        </span>
            )}
        </div>
    );
}

export default ProviderSelector;