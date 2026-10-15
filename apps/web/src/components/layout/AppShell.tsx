import { NavLink, Outlet } from 'react-router';
import { SignOutButton } from './SignOutButton';
import './app-shell.css';

const MAIN_ID = 'main-content';

const useMocks = import.meta.env.VITE_USE_MOCKS === 'true';

const NAV_ITEMS = [
  { to: '/explore', label: 'Explore' },
  { to: '/session', label: 'Session' },
  { to: '/attempts', label: 'Log' },
  { to: '/onboarding', label: 'Setup' },
] as const;

/**
 * Semantic shell: skip link, banner header with the brand and sign-out, primary
 * navigation (bottom bar below 1024px, left rail above), main outlet and a data-note footer.
 */
export const AppShell = () => (
  <div className="shell">
    <a className="skip-link" href={`#${MAIN_ID}`}>
      Skip to main content
    </a>
    <header className="shell__header">
      <p className="brand">
        <span className="brand__name">Beacon Climb</span>
        <span className="brand__tag">Field notebook</span>
      </p>
      <div className="shell__header-tools">
        {useMocks ? (
          <span className="stamp stamp--warning">Mock data</span>
        ) : null}
        <SignOutButton />
      </div>
    </header>
    <nav className="shell__nav" aria-label="Main navigation">
      <ul className="nav-list">
        {NAV_ITEMS.map((item) => (
          <li key={item.to}>
            <NavLink to={item.to} className="nav-link">
              {item.label}
            </NavLink>
          </li>
        ))}
      </ul>
    </nav>
    <main id={MAIN_ID} className="shell__main" tabIndex={-1}>
      <Outlet />
    </main>
    <footer className="shell__footer" aria-label="Privacy and data">
      <p>Pseudonymous username only. No body measurements are collected.</p>
      <p>
        Synthetic demo board. Practice routes made by a generator, not real
        climbs.
      </p>
    </footer>
  </div>
);
