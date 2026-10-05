import React from 'react';
import { Link, NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';

const NAV_ITEMS = [
  { to: '/dashboard', label: 'Dashboard' },
  { to: '/connections', label: 'Connected Accounts' },
  { to: '/scan', label: 'Scan' },
  { to: '/discoveries', label: 'Discovered Accounts' },
  { to: '/services', label: 'Service Directory' },
  { to: '/privacy-requests', label: 'Deletion Requests' },
  { to: '/privacy', label: 'Privacy & Security' },
  { to: '/profile', label: 'Profile' },
  { to: '/settings', label: 'Settings' },
];

function SidebarBody({ onNavigate }: { onNavigate?: () => void }): React.JSX.Element {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const items = user?.admin
    ? [...NAV_ITEMS, { to: '/admin/google-requests', label: 'Admin: Google Requests' }]
    : NAV_ITEMS;

  const handleLogout = async (): Promise<void> => {
    await logout();
    navigate('/login', { replace: true });
  };

  return (
    <>
      <Link to="/dashboard" className="brand" onClick={onNavigate} aria-label="GhostClean home">
        <span className="brand-mark" aria-hidden="true">
          G
        </span>
        GhostClean
      </Link>
      <nav className="nav" aria-label="Primary">
        {items.map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            onClick={onNavigate}
            className={({ isActive }) => (isActive ? 'active' : undefined)}
          >
            {item.label}
          </NavLink>
        ))}
      </nav>
      <div className="nav-footer">
        <button type="button" className="btn" onClick={handleLogout} style={{ width: '100%' }}>
          Log out
        </button>
      </div>
    </>
  );
}

export function Layout(): React.JSX.Element {
  const [open, setOpen] = React.useState(false);

  return (
    <div className="app-shell">
      <a className="skip-link" href="#main-content">
        Skip to content
      </a>
      <div className="topbar">
        <button
          type="button"
          className="hamburger"
          aria-label={open ? 'Close navigation' : 'Open navigation'}
          aria-expanded={open}
          onClick={() => setOpen((v) => !v)}
        >
          ☰
        </button>
        <Link to="/dashboard" className="brand" style={{ border: 'none', padding: 0 }}>
          <span className="brand-mark" aria-hidden="true">
            G
          </span>
          GhostClean
        </Link>
      </div>
      <aside className={`sidebar${open ? ' open' : ''}`} aria-label="Sidebar">
        <SidebarBody onNavigate={() => setOpen(false)} />
      </aside>
      <div className="main">
        <div className="main-inner" id="main-content">
          <Outlet />
        </div>
      </div>
    </div>
  );
}
