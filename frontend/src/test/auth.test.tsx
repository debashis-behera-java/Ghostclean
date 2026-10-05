import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../auth/AuthContext';
import { ProtectedRoute } from '../auth/ProtectedRoute';
import { LoginPage } from '../pages/LoginPage';
import { authApi } from '../api/authApi';
import { userApi } from '../api/userApi';

vi.mock('../api/authApi', () => ({
  authApi: {
    login: vi.fn(),
    refresh: vi.fn(),
    logout: vi.fn(),
  },
}));
vi.mock('../api/userApi', () => ({
  userApi: { me: vi.fn() },
}));
vi.mock('../api/client', () => ({
  setAccessToken: vi.fn(),
  getAccessToken: vi.fn(() => null),
  onAuthFailureSubscribe: vi.fn(() => () => undefined),
}));

const mockedAuth = vi.mocked(authApi);
const mockedUser = vi.mocked(userApi);

function loginScreen() {
  return render(
    <MemoryRouter initialEntries={['/login']}>
      <AuthProvider>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/dashboard" element={<div>Dashboard reached</div>} />
          <Route path="/verify-pending" element={<div>Verify pending</div>} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  );
}

describe('authentication', () => {
  it('logs in and redirects to the dashboard', async () => {
    mockedAuth.refresh.mockRejectedValue(new Error('no session'));
    mockedAuth.login.mockResolvedValue({
      accessToken: 'tok',
      tokenType: 'Bearer',
      expiresIn: 900,
      emailVerified: true,
    });
    mockedUser.me.mockResolvedValue({
      userId: 'u1',
      email: 'a@example.com',
      emailVerified: true,
      admin: false,
      status: 'ACTIVE',
      createdAt: '',
      lastLoginAt: '',
    });
    const user = userEvent.setup();
    loginScreen();
    await waitFor(() => expect(screen.getByLabelText('Email')).toBeInTheDocument());
    await user.type(screen.getByLabelText('Email'), 'a@example.com');
    await user.type(screen.getByLabelText('Password'), 'long-enough-password');
    await user.click(screen.getByRole('button', { name: 'Log in' }));
    await waitFor(() => expect(screen.getByText('Dashboard reached')).toBeInTheDocument());
    expect(mockedAuth.login).toHaveBeenCalledWith('a@example.com', 'long-enough-password');
  });

  it('shows a friendly message on invalid credentials', async () => {
    mockedAuth.refresh.mockRejectedValue(new Error('no session'));
    mockedAuth.login.mockRejectedValue({ response: { data: { code: 'INVALID_CREDENTIALS' } } });
    const user = userEvent.setup();
    loginScreen();
    await waitFor(() => expect(screen.getByLabelText('Email')).toBeInTheDocument());
    await user.type(screen.getByLabelText('Email'), 'a@example.com');
    await user.type(screen.getByLabelText('Password'), 'wrong-password!!');
    await user.click(screen.getByRole('button', { name: 'Log in' }));
    await waitFor(() =>
      expect(screen.getByText('Incorrect email or password.')).toBeInTheDocument(),
    );
  });

  it('protected routes redirect anonymous users to login', async () => {
    mockedAuth.refresh.mockRejectedValue(new Error('no session'));
    render(
      <MemoryRouter initialEntries={['/dashboard']}>
        <AuthProvider>
          <Routes>
            <Route path="/login" element={<div>Login page</div>} />
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
    await waitFor(() => expect(screen.getByText('Login page')).toBeInTheDocument());
  });
});
