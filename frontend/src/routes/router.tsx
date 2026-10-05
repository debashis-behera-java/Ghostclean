import { Navigate, createBrowserRouter } from 'react-router-dom';
import { ProtectedRoute } from '../auth/ProtectedRoute';
import { AdminRoute } from '../auth/AdminRoute';
import { Layout } from '../components/Layout';
import { LandingPage } from '../pages/LandingPage';
import { LoginPage } from '../pages/LoginPage';
import { RegisterPage } from '../pages/RegisterPage';
import { VerifyPendingPage, VerifyResultPage } from '../pages/VerifyPages';
import { ForgotPasswordPage } from '../pages/ForgotPasswordPage';
import { ResetPasswordPage } from '../pages/ResetPasswordPage';
import { DashboardPage } from '../pages/DashboardPage';
import { ConnectionsPage } from '../pages/ConnectionsPage';
import { AdminRequestsPage } from '../pages/AdminRequestsPage';
import { OAuthCallbackPage } from '../pages/OAuthCallbackPage';
import { ScanPage } from '../pages/ScanPage';
import { DiscoveriesPage } from '../pages/DiscoveriesPage';
import { DiscoveryDetailPage } from '../pages/DiscoveryDetailPage';
import { ServicesPage, ServiceDetailPage } from '../pages/ServicesPages';
import { DeletionPreviewPage } from '../pages/DeletionPreviewPage';
import { PrivacyRequestsPage } from '../pages/PrivacyRequestsPage';
import { PrivacyRequestDetailPage } from '../pages/PrivacyRequestDetailPage';
import { TrackingPage } from '../pages/TrackingPage';
import { PrivacyPage } from '../pages/PrivacyPage';
import { ProfilePage, SettingsPage } from '../pages/ProfilePages';
import { NotFoundPage } from '../pages/NotFoundPage';

function Guard({ children }: { children: React.JSX.Element }): React.JSX.Element {
  return <ProtectedRoute>{children}</ProtectedRoute>;
}

export const router = createBrowserRouter([
  { path: '/', element: <LandingPage /> },
  { path: '/login', element: <LoginPage /> },
  { path: '/register', element: <RegisterPage /> },
  { path: '/verify-pending', element: <VerifyPendingPage /> },
  { path: '/verify-result', element: <VerifyResultPage /> },
  { path: '/forgot-password', element: <ForgotPasswordPage /> },
  { path: '/reset-password', element: <ResetPasswordPage /> },
  { path: '/oauth/callback', element: <OAuthCallbackPage /> },
  {
    element: (
      <Guard>
        <Layout />
      </Guard>
    ),
    children: [
      { path: '/dashboard', element: <DashboardPage /> },
      { path: '/connections', element: <ConnectionsPage /> },
      { path: '/scan', element: <ScanPage /> },
      { path: '/discoveries', element: <DiscoveriesPage /> },
      { path: '/discoveries/:id', element: <DiscoveryDetailPage /> },
      { path: '/services', element: <ServicesPage /> },
      { path: '/services/:slug', element: <ServiceDetailPage /> },
      { path: '/deletion/preview', element: <DeletionPreviewPage /> },
      { path: '/privacy-requests', element: <PrivacyRequestsPage /> },
      { path: '/privacy-requests/:id', element: <PrivacyRequestDetailPage /> },
      { path: '/privacy-requests/:id/tracking', element: <TrackingPage /> },
      {
        path: '/admin/google-requests',
        element: (
          <AdminRoute>
            <AdminRequestsPage />
          </AdminRoute>
        ),
      },
      { path: '/privacy', element: <PrivacyPage /> },
      { path: '/profile', element: <ProfilePage /> },
      { path: '/settings', element: <SettingsPage /> },
    ],
  },
  { path: '/home', element: <Navigate to="/dashboard" replace /> },
  { path: '*', element: <NotFoundPage /> },
]);
