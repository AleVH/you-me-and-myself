/**
 * Root application component.
 *
 * Thin wrapper that renders the ChatApp and provides any future
 * context providers (theme, error boundary, etc.) at the top level.
 *
 * Currently minimal — will expand as we add features in R2-R6.
 */
import ChatApp from "./components/ChatApp";

function App() {
    return <ChatApp />;
}

export default App;