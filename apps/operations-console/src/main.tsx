import { createRoot } from 'react-dom/client';
import { App } from './App';
import { initializeIdentity } from './api';
import './style.css';
const root = createRoot(document.getElementById('root')!);
initializeIdentity().then(() => root.render(<App />)).catch(() => root.render(<App identityError="Local sign-in is unavailable. Check that the platform is running, then retry." />));
