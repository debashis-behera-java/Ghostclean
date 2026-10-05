import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../auth/AuthContext';
import { AdminRoute } from '../auth/AdminRoute';
import { ConnectionsPage } from '../pages/ConnectionsPage';
import { AdminRequestsPage } from '../pages/AdminRequestsPage';
import { OAuthCallbackPage } from '../pages/OAuthCallbackPage';
import { authApi } from '../api/authApi';
import { userApi } from '../api/userApi';
import { oauthApi } from '../api/oauthApi';

vi.mock('../api/authApi', () => ({ authApi: { refresh: vi.fn() } }));
vi.mock('../api/userApi', () => ({ userApi: { me: vi.fn() } }));
vi.mock('../api/oauthApi', () => ({
  oauthApi: {
    connections: vi.fn(),
    authorize: vi.fn(),
    disconnect: vi.fn(),
    refreshConnection: vi.fn(),
    googleApprovalStatus: vi.fn(),
    requestGoogleApproval: vi.fn(),
    cancelGoogleApproval: vi.fn(),
    adminGoogleRequests: vi.fn(),
    adminApproveGoogleRequest: vi.fn(),
    adminRejectGoogleRequest: vi.fn(),
  },
}));
vi.mock('../api/client', () => ({
  setAccessToken: vi.fn(),
  getAccessToken: vi.fn(() => null),
  onAuthFailureSubscribe: vi.fn(() => () => undefined),
}));

const baseProfile = {
  userId: 'u1',
  email: 'a@example.com',
  emailVerified: true,
  admin: false,
  status: 'ACTIVE',
  createdAt: '',
  lastLoginAt: '',
};

function authedAs(admin: boolean) {
  vi.mocked(authApi).refresh.mockResolvedValue({
    accessToken: 't',
    tokenType: 'Bearer',
    expiresIn: 900,
    emailVerified: true,
  });
  vi.mocked(userApi).me.mockResolvedValue({ ...baseProfile, admin });
  vi.mocked(oauthApi).connections.mockResolvedValue([]);
}

function connectionsScreen() {
  return render(
    <MemoryRouter initialEntries={['/connections']}>
      <AuthProvider>
        <ConnectionsPage />
      </AuthProvider>
    </MemoryRouter>,
  );
}

describe('google approval user flow', () => {
  it('shows pending state and never starts OAuth while pending', async () => {
    authedAs(false);
    vi.mocked(oauthApi).googleApprovalStatus.mockResolvedValue({
      requestId: 'r1',
      userId: 'u1',
      userEmail: null,
      status: 'PENDING',
      requestedAt: '',
      reviewedAt: null,
      rejectionReason: null,
      retryAvailableAt: null,
    });
    vi.mocked(oauthApi).requestGoogleApproval.mockResolvedValue({
      requestId: 'r1',
      userId: 'u1',
      userEmail: null,
      status: 'PENDING',
      requestedAt: '',
      reviewedAt: null,
      rejectionReason: null,
      retryAvailableAt: null,
    });
    const user = userEvent.setup();
    connectionsScreen();
    await waitFor(() =>
      expect(screen.getByText(/waiting for administrator approval/)).toBeInTheDocument(),
    );
    await user.click(screen.getByText('Connect Google'));
    await waitFor(() => expect(vi.mocked(oauthApi).requestGoogleApproval).toHaveBeenCalled());
    expect(vi.mocked(oauthApi).authorize).not.toHaveBeenCalled();
  });

  it('shows approval and starts existing OAuth flow after approval', async () => {
    authedAs(false);
    vi.mocked(oauthApi).googleApprovalStatus.mockResolvedValue({
      requestId: 'r1',
      userId: 'u1',
      userEmail: null,
      status: 'APPROVED',
      requestedAt: '',
      reviewedAt: null,
      rejectionReason: null,
      retryAvailableAt: null,
    });
    vi.mocked(oauthApi).requestGoogleApproval.mockResolvedValue({
      requestId: 'r1',
      userId: 'u1',
      userEmail: null,
      status: 'APPROVED',
      requestedAt: '',
      reviewedAt: null,
      rejectionReason: null,
      retryAvailableAt: null,
    });
    vi.mocked(oauthApi).authorize.mockResolvedValue({
      authorizationUrl: 'https://accounts.google.com/o/oauth2/auth?x=1',
      state: 's',
      expiresAt: '',
    });
    const user = userEvent.setup();
    connectionsScreen();
    await waitFor(() =>
      expect(screen.getByText(/has been approved/)).toBeInTheDocument(),
    );
    await user.click(screen.getByText('Connect Google'));
    await waitFor(() =>
      expect(vi.mocked(oauthApi).authorize).toHaveBeenCalledWith('google'),
    );
  });

  it('shows rejection with the backend reason', async () => {
    authedAs(false);
    vi.mocked(oauthApi).googleApprovalStatus.mockResolvedValue({
      requestId: 'r1',
      userId: 'u1',
      userEmail: null,
      status: 'REJECTED',
      requestedAt: '',
      reviewedAt: '',
      rejectionReason: 'Try again later',
      retryAvailableAt: null,
    });
    connectionsScreen();
    await waitFor(() =>
      expect(screen.getByText(/was not approved/)).toBeInTheDocument(),
    );
    expect(screen.getByText(/Try again later/)).toBeInTheDocument();
  });

  it('tells approved users about the manual Google test-user step', async () => {
    authedAs(false);
    vi.mocked(oauthApi).googleApprovalStatus.mockResolvedValue({
      requestId: 'r1',
      userId: 'u1',
      userEmail: null,
      status: 'APPROVED',
      requestedAt: '',
      reviewedAt: null,
      rejectionReason: null,
      retryAvailableAt: null,
    });
    connectionsScreen();
    await waitFor(() =>
      expect(screen.getByText(/cannot do this automatically/)).toBeInTheDocument(),
    );
    expect(screen.getByText(/Test users/)).toBeInTheDocument();
  });
});

describe('oauth denied guidance', () => {
  it('explains the test-user step when Google denies access', () => {
    render(
      <MemoryRouter initialEntries={['/oauth/callback?provider=google&status=error&error=OAUTH_DENIED']}>
        <OAuthCallbackPage />
      </MemoryRouter>,
    );
    expect(screen.getByText(/is not listed as a Test User/)).toBeInTheDocument();
    expect(screen.getByText(/cannot add it for you/)).toBeInTheDocument();
  });
});

describe('admin dashboard', () => {
  const queue = [
    {
      requestId: 'r1',
      userId: 'u9',
      userEmail: 'user9@example.com',
      status: 'PENDING',
      requestedAt: '',
      reviewedAt: null,
      reviewedBy: null,
      rejectionReason: null,
    },
  ];

  function adminScreen() {
    return render(
      <MemoryRouter initialEntries={['/admin/google-requests']}>
        <AuthProvider>
          <Routes>
            <Route
              path="/admin/google-requests"
              element={
                <AdminRoute>
                  <AdminRequestsPage />
                </AdminRoute>
              }
            />
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    );
  }

  it('blocks non-admin users', async () => {
    authedAs(false);
    adminScreen();
    await waitFor(() => expect(screen.getByText('Not authorized')).toBeInTheDocument());
    expect(vi.mocked(oauthApi).adminGoogleRequests).not.toHaveBeenCalled();
  });

  it('lists requests and approves with confirmation', async () => {
    authedAs(true);
    vi.mocked(oauthApi).adminGoogleRequests.mockResolvedValue(queue);
    vi.mocked(oauthApi).adminApproveGoogleRequest.mockResolvedValue({ ...queue[0], status: 'APPROVED' });
    const user = userEvent.setup();
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
    adminScreen();
    await waitFor(() => expect(screen.getByText('user9@example.com')).toBeInTheDocument());
    await user.click(screen.getByText('Approve'));
    await waitFor(() =>
      expect(vi.mocked(oauthApi).adminApproveGoogleRequest).toHaveBeenCalledWith('r1'),
    );
    confirm.mockRestore();
  });

  it('rejects with a reason through the confirmation dialog', async () => {
    authedAs(true);
    vi.mocked(oauthApi).adminGoogleRequests.mockResolvedValue(queue);
    vi.mocked(oauthApi).adminRejectGoogleRequest.mockResolvedValue({ ...queue[0], status: 'REJECTED' });
    const user = userEvent.setup();
    adminScreen();
    await waitFor(() => expect(screen.getByText('user9@example.com')).toBeInTheDocument());
    await user.click(screen.getByText('Reject'));
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByLabelText(/Reason/), 'Not yet');
    await user.click(within(dialog).getByText('Confirm rejection'));
    await waitFor(() =>
      expect(vi.mocked(oauthApi).adminRejectGoogleRequest).toHaveBeenCalledWith('r1', 'Not yet'),
    );
  });

  it('never renders token material', async () => {
    authedAs(true);
    vi.mocked(oauthApi).adminGoogleRequests.mockResolvedValue(queue);
    const { container } = adminScreen();
    await waitFor(() => expect(screen.getByText('user9@example.com')).toBeInTheDocument());
    expect(container.textContent).not.toMatch(/acc-|ref-|Bearer|access_token|refresh_token/i);
  });
});
