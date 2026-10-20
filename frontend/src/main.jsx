import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import App from './app/App.jsx';
import { applyTheme, readPreferences, themeOf } from './features/settings/preferences.js';
import './styles.css';

// The chosen colour theme before the first paint (admin Phase 12).
applyTheme(themeOf(readPreferences()));

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
