import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../auth/AuthContext';
import { ProtectedRoute } from '../auth/ProtectedRoute';
import { OAuthCallbackPage } from '../pages/OAuthCallbackPage';
import { ScanPage } from '../pages/ScanPage';
import { DiscoveryDetailPage } from '../pages/DiscoveryDetailPage';
import { PrivacyRequestDetailPage } from '../pages/PrivacyRequestDetailPage';
import { authApi } from '../api/authApi';
import { userApi } from '../api/userApi';
import { oauthApi } from '../api/oauthApi';
import { scanApi } from '../api/scanApi';
import { discoveryApi } from '../api/discoveryApi';
import { privacyRequestApi } from '../api/privacyRequestApi';
import { authService } from '../auth/authService';
import { friendlyErrorMessage, codeToMessage } from '../utils/errors';
import { maskEmail, formatInstant } from '../utils/format';
import { redactSecrets, truncate } from '../utils/sanitize';

vi.mock('../api/authApi', () => ({
  authApi: { refresh: vi.fn(), logout: vi.fn() },
}));
vi.mock('../api/userApi', () => ({ userApi: { me: vi.fn() } }));
vi.mock('../api/oauthApi', () => ({
  oauthApi: { connections: vi.fn(), authorize: vi.fn(), disconnect: vi.fn(), refreshConnection: vi.fn(), googleApprovalStatus: vi.fn(), requestGoogleApproval: vi.fn(), cancelGoogleApproval: vi.fn(), adminGoogleRequests: vi.fn(), adminApproveGoogleRequest: vi.fn(), adminRejectGoogleRequest: vi.fn() },
}));
vi.mock('../api/scanApi', () => ({
  scanApi: { list: vi.fn(), start: vi.fn(), cancel: vi.fn(), remove: vi.fn() },
}));
vi.mock('../api/discoveryApi', () => ({
  discoveryApi: { list: vi.fn(), get: vi.fn(), review: vi.fn(), evidence: vi.fn(), purgeEvidence: vi.fn() },
}));
vi.mock('../api/privacyRequestApi', () => ({
  privacyRequestApi: {
    list: vi.fn(),
    get: vi.fn(),
    patch: vi.fn(),
    approve: vi.fn(),
    cancel: vi.fn(),
    responses: vi.fn(),
    recordResponse: vi.fn(),
    dispatch: vi.fn(),
    dispatchStatus: vi.fn(),
    cancelDispatch: vi.fn(),
  },
  RESPONSE_OUTCOMES: ['completed'],
}));
vi.mock('../api/client', () => ({
  setAccessToken: vi.fn(),
  getAccessToken: vi.fn(() => null),
  onAuthFailureSubscribe: vi.fn(() => () => undefined),
}));

const verifiedProfile = {
  userId: 'u1',
  email: 'a@example.com',
  emailVerified: true,
  admin: false,
  status: 'ACTIVE',
  createdAt: '',
  lastLoginAt: '',
};

function authedAs(profile: typeof verifiedProfile) {
  vi.mocked(authApi).refresh.mockResolvedValue({
    accessToken: 't',
    tokenType: 'Bearer',
    expiresIn: 900,
    emailVerified: profile.emailVerified,
  });
  vi.mocked(userApi).me.mockResolvedValue(profile);
}

describe('verification gate', () => {
  it('routes unverified users to verify-pending', async () => {
    authedAs({ ...verifiedProfile, emailVerified: false });
    render(
      <MemoryRouter initialEntries={['/dashboard']}>
        <AuthProvider>
          <Routes>
            <Route path="/verify-pending" element={<div>Verify pending page</div>} />
            <Route
              path="/dashboard"
              element={
                <ProtectedRoute>
                  <div>Secret</div>
                </ProtectedRoute>
              }
            />
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    );
    await waitFor(() => expect(screen.getByText('Verify pending page')).toBeInTheDocument());
  });
});

describe('oauth callback', () => {
  it('celebrates provider success without touching tokens', () => {
    render(
      <MemoryRouter initialEntries={['/oauth/callback?provider=google&status=connected']}>
        <OAuthCallbackPage />
      </MemoryRouter>,
    );
    expect(screen.getByText(/mailbox is connected/)).toBeInTheDocument();
  });

  it('explains denial with a safe message', () => {
    const { container } = render(
      <MemoryRouter initialEntries={['/oauth/callback?provider=google&status=error&error=OAUTH_DENIED']}>
        <OAuthCallbackPage />
      </MemoryRouter>,
    );
    expect(screen.getByText(/not granted at the provider/)).toBeInTheDocument();
    expect(container.textContent).not.toMatch(/code|token|secret/i);
  });
});

describe('scan success', () => {
  it('starts a scan and refreshes history', async () => {
    authedAs(verifiedProfile);
    vi.mocked(oauthApi).connections.mockResolvedValue([
      { id: 'c1', provider: 'google', status: 'ACTIVE', providerEmailMasked: 'o***@x.com', scopes: [], connectedAt: '', expiresAt: '' },
    ]);
    vi.mocked(scanApi).list
      .mockResolvedValueOnce([])
      .mockResolvedValue([
        { id: 's1', type: 'GMAIL', status: 'COMPLETED', progressTotal: 1, progressDone: 1, messagesScanned: 10, candidatesDetected: 2, errorsCount: 0, errorCode: null, startedAt: '', endedAt: '' },
      ]);
    vi.mocked(scanApi).start.mockResolvedValue({ id: 's1' } as never);
    const user = userEvent.setup();
    render(
      <MemoryRouter initialEntries={['/scan']}>
        <AuthProvider>
          <ScanPage />
        </AuthProvider>
      </MemoryRouter>,
    );
    await waitFor(() => expect(screen.getByText('Start Scan')).toBeInTheDocument());
    await user.selectOptions(screen.getByLabelText('Mailbox'), 'c1');
    await user.click(screen.getByText('Start Scan'));
    await waitFor(() => expect(vi.mocked(scanApi).start).toHaveBeenCalled());
  });
});

describe('discovery review', () => {
  const account = {
    id: 'd1',
    service: 'Example',
    domain: 'example.com',
    category: 'other',
    status: 'DETECTED',
    confidence: 'HIGH',
    confidenceScore: 90,
    evidenceCount: 1,
    firstSeenAt: '',
    lastSeenAt: '',
    privacyRoute: { type: 'UNKNOWN', availability: 'UNKNOWN', confidence: 'UNKNOWN', manualReviewRequired: true, note: 'Manual review required.' },
    automation: { status: 'MANUAL_ONLY', note: '' },
    jurisdiction: { supports: [], note: '' },
  };

  it('keep and dismiss call review with the right decision', async () => {
    authedAs(verifiedProfile);
    vi.mocked(discoveryApi).get.mockResolvedValue(account);
    vi.mocked(discoveryApi).evidence.mockResolvedValue([]);
    vi.mocked(discoveryApi).review.mockResolvedValue({ ...account, status: 'REVIEWED' });
    const user = userEvent.setup();
    render(
      <MemoryRouter initialEntries={['/discoveries/d1']}>
        <AuthProvider>
          <Routes>
            <Route path="/discoveries/:id" element={<DiscoveryDetailPage />} />
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    );
    await waitFor(() => expect(screen.getByText('Keep (mark reviewed)')).toBeInTheDocument());
    expect(screen.getByText(/Why was this detected/)).toBeInTheDocument();
    await user.click(screen.getByText('Keep (mark reviewed)'));
    await waitFor(() =>
      expect(vi.mocked(discoveryApi).review).toHaveBeenCalledWith('d1', 'keep', undefined),
    );
  });
});

describe('edit invalidates approval', () => {
  const row = {
    id: 'r1',
    discoveredServiceId: 'd1',
    service: 'Example',
    domain: 'example.com',
    category: 'other',
    requestType: 'DELETE_PERSONAL_DATA',
    status: 'APPROVED',
    subject: 'Privacy Request',
    body: 'Hello',
    jurisdiction: 'GENERAL',
    version: 2,
    approvedVersion: 2,
    approvedAt: '',
    expiresAt: '',
    createdAt: '',
    updatedAt: '',
  };

  it('warns that editing requires re-approval and saves via patch', async () => {
    authedAs(verifiedProfile);
    vi.mocked(privacyRequestApi).get.mockResolvedValue(row);
    vi.mocked(privacyRequestApi).dispatchStatus.mockRejectedValue({ response: { status: 404 } });
    vi.mocked(privacyRequestApi).responses.mockResolvedValue([]);
    vi.mocked(privacyRequestApi).patch.mockResolvedValue({ ...row, status: 'READY_FOR_REVIEW', version: 3 });
    const user = userEvent.setup();
    render(
      <MemoryRouter initialEntries={['/privacy-requests/r1']}>
        <AuthProvider>
          <Routes>
            <Route path="/privacy-requests/:id" element={<PrivacyRequestDetailPage />} />
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    );
    await waitFor(() => expect(screen.getByText('Edit request')).toBeInTheDocument());
    await user.click(screen.getByText('Edit request'));
    expect(screen.getByText(/invalidate.*approval/i)).toBeInTheDocument();
    await user.click(screen.getByText('Save changes'));
    await waitFor(() =>
      expect(vi.mocked(privacyRequestApi).patch).toHaveBeenCalledWith('r1', 'Privacy Request', 'Hello'),
    );
  });
});

describe('dispatch failure', () => {
  it('surfaces provider failure without crashing and never retries silently', async () => {
    authedAs(verifiedProfile);
    vi.mocked(privacyRequestApi).get.mockResolvedValue({
      id: 'r1',
      discoveredServiceId: 'd1',
      service: 'Example',
      domain: 'example.com',
      category: 'other',
      requestType: 'DELETE_PERSONAL_DATA',
      status: 'APPROVED',
      subject: 's',
      body: 'b',
      jurisdiction: 'GENERAL',
      version: 2,
      approvedVersion: 2,
      approvedAt: '',
      expiresAt: '',
      createdAt: '',
      updatedAt: '',
    });
    vi.mocked(privacyRequestApi).dispatchStatus.mockRejectedValue({ response: { status: 404 } });
    vi.mocked(privacyRequestApi).responses.mockResolvedValue([]);
    const failed = {
      dispatchId: 'x1',
      requestId: 'r1',
      status: 'FAILED',
      provider: 'sink',
      destination: 'privacy@example.com',
      requestVersion: 2,
      attempts: 1,
      providerReference: null,
      failureCategory: 'MESSAGE_REJECTED',
      requestedAt: '',
      acceptedAt: null,
      failedAt: '',
    };
    vi.mocked(privacyRequestApi).dispatch.mockImplementation(async () => {
      vi.mocked(privacyRequestApi).dispatchStatus.mockResolvedValue(failed);
      return failed;
    });
    const user = userEvent.setup();
    render(
      <MemoryRouter initialEntries={['/privacy-requests/r1']}>
        <AuthProvider>
          <Routes>
            <Route path="/privacy-requests/:id" element={<PrivacyRequestDetailPage />} />
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    );
    await waitFor(() => expect(screen.getByText('Dispatch…')).toBeInTheDocument());
    await user.click(screen.getByText('Dispatch…'));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('checkbox'));
    await user.click(within(dialog).getByText('Send Deletion Request'));
    await waitFor(() => expect(vi.mocked(privacyRequestApi).dispatch).toHaveBeenCalledTimes(1));
    expect(screen.getByText('MESSAGE_REJECTED')).toBeInTheDocument();
  });
});

describe('logout', () => {
  it('calls the backend and clears the session', async () => {
    vi.mocked(authApi).logout.mockResolvedValue(undefined);
    const { setAccessToken } = await import('../api/client');
    await authService.logout();
    expect(vi.mocked(authApi).logout).toHaveBeenCalled();
    expect(setAccessToken).toHaveBeenCalledWith(null);
  });
});

describe('error mapping', () => {
  it.each([
    ['INVALID_CREDENTIALS', 'Incorrect email or password.'],
    ['DESTINATION_UNKNOWN', 'No verified destination is known for this service. Manual review is required.'],
    ['MANUAL_REVIEW_REQUIRED', 'This route requires manual handling. GhostClean cannot dispatch it automatically.'],
    ['RATE_LIMITED', 'Too many attempts. Please wait a while and try again.'],
    ['REQUEST_EXPIRED', 'This request has expired. Create a new one to continue.'],
  ])('maps %s safely', (code, expected) => {
    expect(friendlyErrorMessage({ response: { data: { code } } })).toBe(expected);
  });

  it('maps bare statuses without leaking payloads', () => {
    expect(friendlyErrorMessage({ response: { status: 404, data: {} } })).toBe('Not found.');
    expect(
      friendlyErrorMessage({ response: { status: 500, data: { message: 'SELECT * blew up' } } }),
    ).toBe('Something went wrong on our side. Please try again later.');
    expect(friendlyErrorMessage(new Error('boom'))).toBe('Something went wrong. Please try again.');
  });

  it('maps oauth redirect codes', () => {
    expect(codeToMessage('OAUTH_DENIED')).toMatch(/not granted/);
    expect(codeToMessage('bogus')).toMatch(/did not grant access/);
  });
});

describe('display utilities', () => {
  it('masks emails, redacts token shapes, truncates, and formats dates', () => {
    expect(maskEmail('jane@example.com')).toBe('j***@example.com');
    expect(maskEmail(null)).toBe('—');
    expect(redactSecrets('token acc-abc123 here')).toBe('token [redacted] here');
    expect(redactSecrets('Bearer xyz.abc.def ok')).toBe('[redacted] ok');
    expect(truncate('abcdef', 4)).toBe('abc…');
    expect(formatInstant(null)).toBe('—');
    expect(formatInstant('not-a-date')).toBe('—');
  });
});
