import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../auth/AuthContext';
import { DashboardPage } from '../pages/DashboardPage';
import { ConnectionsPage } from '../pages/ConnectionsPage';
import { ScanPage } from '../pages/ScanPage';
import { DiscoveriesPage } from '../pages/DiscoveriesPage';
import { DeletionPreviewPage } from '../pages/DeletionPreviewPage';
import { PrivacyRequestDetailPage } from '../pages/PrivacyRequestDetailPage';
import { methodExplanation } from '../pages/ServicesPages';
import { authApi } from '../api/authApi';
import { userApi } from '../api/userApi';
import { oauthApi } from '../api/oauthApi';
import { scanApi } from '../api/scanApi';
import { discoveryApi } from '../api/discoveryApi';
import { deletionApi } from '../api/deletionApi';
import { privacyRequestApi } from '../api/privacyRequestApi';

vi.mock('../api/authApi', () => ({ authApi: { refresh: vi.fn() } }));
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
vi.mock('../api/deletionApi', () => ({
  deletionApi: { preview: vi.fn(), create: vi.fn(), get: vi.fn() },
  REQUEST_TYPES: ['DELETE_PERSONAL_DATA'],
  JURISDICTIONS: ['GENERAL'],
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

const profile = {
  userId: 'u1',
  email: 'a@example.com',
  emailVerified: true,
  admin: false,
  status: 'ACTIVE',
  createdAt: '',
  lastLoginAt: '',
};

function authed() {
  vi.mocked(authApi).refresh.mockResolvedValue({
    accessToken: 't',
    tokenType: 'Bearer',
    expiresIn: 900,
    emailVerified: true,
  });
  vi.mocked(userApi).me.mockResolvedValue(profile);
}

function shell(path: string, element: React.JSX.Element, route = '*') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <Routes>
          <Route path={route} element={element} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  );
}

describe('dashboard empty state', () => {
  it('shows no-data messaging instead of invented numbers', async () => {
    authed();
    vi.mocked(oauthApi).connections.mockResolvedValue([]);
    vi.mocked(scanApi).list.mockResolvedValue([]);
    vi.mocked(discoveryApi).list.mockResolvedValue([]);
    vi.mocked(privacyRequestApi).list.mockResolvedValue([]);
    shell('/dashboard', <DashboardPage />, '/dashboard');
    await waitFor(() => expect(screen.getByText('No scan completed yet.')).toBeInTheDocument());
    expect(screen.getByText('No accounts discovered yet. Run a scan to begin.')).toBeInTheDocument();
  });
});

describe('connections', () => {
  it('lists providers without any token material', async () => {
    authed();
    vi.mocked(oauthApi).googleApprovalStatus.mockResolvedValue({
      requestId: null,
      userId: 'u1',
      userEmail: null,
      status: 'NONE',
      requestedAt: null,
      reviewedAt: null,
      rejectionReason: null,
      retryAvailableAt: null,
    });
    vi.mocked(oauthApi).connections.mockResolvedValue([
      {
        id: 'c1',
        provider: 'google',
        status: 'ACTIVE',
        providerEmailMasked: 'o***@example.com',
        scopes: ['https://www.googleapis.com/auth/gmail.readonly'],
        connectedAt: '2026-01-01T00:00:00Z',
        expiresAt: '2026-02-01T00:00:00Z',
      },
    ]);
    const { container } = shell('/connections', <ConnectionsPage />, '/connections');
    await waitFor(() => expect(screen.getByText('google')).toBeInTheDocument());
    expect(container.textContent).not.toMatch(/acc-|ref-|Bearer|access_token|refresh_token/i);
  });
});

describe('scan', () => {
  it('starts a scan for the chosen mailbox and surfaces failures', async () => {
    authed();
    vi.mocked(oauthApi).connections.mockResolvedValue([
      {
        id: 'c1',
        provider: 'google',
        status: 'ACTIVE',
        providerEmailMasked: 'o***@example.com',
        scopes: [],
        connectedAt: '',
        expiresAt: '',
      },
    ]);
    vi.mocked(scanApi).list.mockResolvedValue([]);
    vi.mocked(scanApi).start.mockRejectedValue({
      response: { data: { code: 'GOOGLE_REAUTH_REQUIRED' } },
    });
    const user = userEvent.setup();
    shell('/scan', <ScanPage />, '/scan');
    await waitFor(() => expect(screen.getByText('Start Scan')).toBeInTheDocument());
    await user.selectOptions(screen.getByLabelText('Mailbox'), 'c1');
    await user.click(screen.getByText('Start Scan'));
    await waitFor(() =>
      expect(screen.getByText(/mailbox connection needs attention/i)).toBeInTheDocument(),
    );
    expect(vi.mocked(scanApi).start).toHaveBeenCalledWith('c1', expect.any(String));
  });
});

describe('discoveries', () => {
  it('renders backend rows with filters', async () => {
    authed();
    vi.mocked(discoveryApi).list.mockResolvedValue([
      {
        id: 'd1',
        service: 'Example',
        domain: 'example.com',
        category: 'other',
        status: 'DETECTED',
        confidence: 'HIGH',
        confidenceScore: 90,
        evidenceCount: 2,
        firstSeenAt: '',
        lastSeenAt: '',
        privacyRoute: { type: 'UNKNOWN', availability: 'UNKNOWN', confidence: 'UNKNOWN', manualReviewRequired: true, note: '' },
        automation: { status: 'MANUAL_ONLY', note: '' },
        jurisdiction: { supports: [], note: '' },
      },
    ]);
    const user = userEvent.setup();
    shell('/discoveries', <DiscoveriesPage />, '/discoveries');
    await waitFor(() => expect(screen.getByText('Example')).toBeInTheDocument());
    await user.selectOptions(screen.getByLabelText('Confidence'), 'LOW');
    expect(screen.getByText('No discoveries match these filters.')).toBeInTheDocument();
  });
});

describe('deletion preview (dry run)', () => {
  const previewPayload = {
    discoveredServiceId: 'd1',
    service: 'Example',
    domain: 'example.com',
    category: 'other',
    requestType: 'DELETE_PERSONAL_DATA',
    jurisdiction: 'GENERAL',
    deletionMethod: 'UNKNOWN',
    manualReviewRequired: true,
    supportedTypes: ['DELETE_PERSONAL_DATA'],
    destination: null,
    subject: 'Privacy Request',
    body: 'Hello',
    templateId: 'privacy-request-v1',
    templateVersion: 1,
    warnings: ['GhostClean does not guarantee deletion.'],
    manualInstructions: ['Manual review is required.'],
  };

  it('labels the dry run and never dispatches from preview', async () => {
    authed();
    vi.mocked(deletionApi).preview.mockResolvedValue(previewPayload);
    vi.mocked(discoveryApi).get.mockResolvedValue({ service: 'Example' } as never);
    const user = userEvent.setup();
    shell('/deletion/preview?accountId=d1', <DeletionPreviewPage />, '/deletion/preview');
    await user.click(screen.getByText('Show preview'));
    await waitFor(() => expect(screen.getByText(/DRY RUN — nothing has been sent/)).toBeInTheDocument());
    expect(vi.mocked(privacyRequestApi).dispatch).not.toHaveBeenCalled();
  });

  it('creates a draft and navigates to the request page', async () => {
    authed();
    vi.mocked(deletionApi).preview.mockResolvedValue(previewPayload);
    vi.mocked(deletionApi).create.mockResolvedValue({ id: 'r1' } as never);
    vi.mocked(discoveryApi).get.mockResolvedValue({ service: 'Example' } as never);
    const user = userEvent.setup();
    render(
      <MemoryRouter initialEntries={['/deletion/preview?accountId=d1']}>
        <AuthProvider>
          <Routes>
            <Route path="/deletion/preview" element={<DeletionPreviewPage />} />
            <Route path="/privacy-requests/:id" element={<div>Request page</div>} />
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    );
    await user.click(screen.getByText('Show preview'));
    await waitFor(() => expect(screen.getByText('Create Request (stores a draft only)')).toBeInTheDocument());
    await user.click(screen.getByText('Create Request (stores a draft only)'));
    await waitFor(() => expect(screen.getByText('Request page')).toBeInTheDocument());
    expect(vi.mocked(deletionApi).create).toHaveBeenCalled();
    expect(vi.mocked(privacyRequestApi).approve).not.toHaveBeenCalled();
    expect(vi.mocked(privacyRequestApi).dispatch).not.toHaveBeenCalled();
  });
});

describe('request lifecycle safety', () => {
  const row = {
    id: 'r1',
    discoveredServiceId: 'd1',
    service: 'Example',
    domain: 'example.com',
    category: 'other',
    requestType: 'DELETE_PERSONAL_DATA',
    status: 'READY_FOR_REVIEW',
    subject: 'Privacy Request',
    body: 'Hello',
    jurisdiction: 'GENERAL',
    version: 2,
    approvedVersion: null,
    approvedAt: null,
    expiresAt: '',
    createdAt: '',
    updatedAt: '',
  };

  function detail(status: string) {
    vi.mocked(privacyRequestApi).get.mockResolvedValue({ ...row, status });
    vi.mocked(privacyRequestApi).dispatchStatus.mockRejectedValue({ response: { status: 404 } });
    vi.mocked(privacyRequestApi).responses.mockResolvedValue([]);
    return render(
      <MemoryRouter initialEntries={['/privacy-requests/r1']}>
        <AuthProvider>
          <Routes>
            <Route path="/privacy-requests/:id" element={<PrivacyRequestDetailPage />} />
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    );
  }

  it('approval calls approve with the exact version and never dispatches', async () => {
    authed();
    const user = userEvent.setup();
    detail('READY_FOR_REVIEW');
    await waitFor(() => expect(screen.getByText('Approve Request')).toBeInTheDocument());
    await user.click(screen.getByText('Approve Request'));
    await user.click(screen.getByText('Confirm approval of v2'));
    await waitFor(() =>
      expect(vi.mocked(privacyRequestApi).approve).toHaveBeenCalledWith('r1', 2),
    );
    expect(vi.mocked(privacyRequestApi).dispatch).not.toHaveBeenCalled();
  });

  it('dispatch requires the explicit confirmation checkbox', async () => {
    authed();
    const user = userEvent.setup();
    detail('APPROVED');
    await waitFor(() => expect(screen.getByText('Dispatch…')).toBeInTheDocument());
    await user.click(screen.getByText('Dispatch…'));
    const dialog = await screen.findByRole('dialog');
    const send = within(dialog).getByText('Send Deletion Request');
    expect(send).toBeDisabled();
    await user.click(within(dialog).getByRole('checkbox'));
    expect(send).toBeEnabled();
    await user.click(send);
    await waitFor(() =>
      expect(vi.mocked(privacyRequestApi).dispatch).toHaveBeenCalledWith(
        'r1',
        2,
        expect.any(String),
      ),
    );
  });
});

describe('manual routes stay manual', () => {
  it('never presents portal/form/settings routes as automatic', () => {
    for (const method of ['PRIVACY_PORTAL', 'ACCOUNT_SETTINGS', 'WEB_FORM', 'UNKNOWN', 'MANUAL']) {
      expect(methodExplanation(method)).toMatch(/manual/i);
      expect(methodExplanation(method)).not.toMatch(/automatic deletion/i);
    }
    expect(methodExplanation('EMAIL')).toMatch(/explicit/i);
  });
});
