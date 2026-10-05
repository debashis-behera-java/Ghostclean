/**
 * Backend DTO shapes. Field names mirror the Spring Boot records exactly
 * (see backend controllers). Nothing here is invented: unknown payloads are
 * treated as opaque and rendered defensively.
 */

// -- auth --------------------------------------------------------------------

export interface LoginResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  emailVerified: boolean;
}

export interface RegisterResponse {
  message: string;
  verificationRequired: boolean;
}

// -- user --------------------------------------------------------------------

export interface UserProfile {
  userId: string;
  email: string;
  emailVerified: boolean;
  admin: boolean;
  status: string;
  createdAt: string;
  lastLoginAt: string;
}

// -- oauth -------------------------------------------------------------------

export interface AuthorizeResponse {
  authorizationUrl: string;
  state: string;
  expiresAt: string;
}

export interface OAuthConnection {
  id: string;
  provider: string;
  status: string;
  providerEmailMasked: string;
  scopes: string[];
  connectedAt: string;
  expiresAt: string;
}

// -- scans -------------------------------------------------------------------

export interface ScanRecord {
  id: string;
  type: string;
  status: string;
  progressTotal: number | null;
  progressDone: number;
  messagesScanned: number;
  candidatesDetected: number;
  errorsCount: number;
  errorCode: string | null;
  startedAt: string;
  endedAt: string | null;
}

// -- discovered ----------------------------------------------------------------

export interface PrivacyRoute {
  type: string;
  availability: string;
  confidence: string;
  manualReviewRequired: boolean;
  note: string;
}

export interface AutomationInfo {
  status: string;
  note: string;
}

export interface JurisdictionInfo {
  supports: string[];
  note: string;
}

export interface DiscoveredAccount {
  id: string;
  service: string;
  domain: string;
  category: string;
  status: string;
  confidence: string;
  confidenceScore: number;
  evidenceCount: number;
  firstSeenAt: string;
  lastSeenAt: string;
  privacyRoute: PrivacyRoute;
  automation: AutomationInfo;
  jurisdiction: JurisdictionInfo;
}

export interface EvidenceRow {
  id: string;
  source: string;
  sourceDomain: string;
  subject: string;
  observedAt: string;
  weight: number;
  matchedRules: string[];
  createdAt: string;
}

// -- services ------------------------------------------------------------------

export interface ServiceSummary {
  serviceId: string;
  displayName: string;
  category: string;
  routeAvailability: string;
  automationStatus: string;
}

export interface RouteSource {
  url: string | null;
  type: string;
  verificationStatus: string;
  lastVerifiedAt: string | null;
}

export interface ServiceDetail {
  serviceId: string;
  displayName: string;
  category: string;
  domains: string[];
  senderAliases: string[];
  accountManagementUrl: string | null;
  privacyUrl: string | null;
  deletionUrl: string | null;
  privacyEmail: string | null;
  routeType: string;
  requestMethod: string;
  routeConfidence: string;
  routeAvailability: string;
  manualReviewRequired: boolean;
  routeNote: string;
  automationStatus: string;
  automationNote: string;
  jurisdiction: string[];
  jurisdictionNote: string;
  source: RouteSource;
  lastVerifiedAt: string | null;
}

// -- deletion -------------------------------------------------------------------

export interface DeletionPreview {
  discoveredServiceId: string;
  service: string;
  domain: string;
  category: string;
  requestType: string;
  jurisdiction: string;
  deletionMethod: string;
  manualReviewRequired: boolean;
  supportedTypes: string[];
  destination: string | null;
  subject: string;
  body: string;
  templateId: string;
  templateVersion: number;
  warnings: string[];
  manualInstructions: string[];
}

export interface DeletionRequestView extends DeletionPreview {
  id: string;
  status: string;
  version: number;
  approvedVersion: number | null;
  approvedAt: string | null;
  expiresAt: string;
  createdAt: string;
  updatedAt: string;
}

// -- privacy requests -------------------------------------------------------------

export interface PrivacyRequestSummary {
  id: string;
  discoveredServiceId: string;
  service: string;
  domain: string;
  category: string;
  requestType: string;
  status: string;
  subject: string;
  body: string;
  jurisdiction: string;
  version: number;
  approvedVersion: number | null;
  approvedAt: string | null;
  expiresAt: string;
  createdAt: string;
  updatedAt: string;
}

export interface DispatchView {
  dispatchId: string;
  requestId: string;
  status: string;
  provider: string;
  destination: string;
  requestVersion: number;
  attempts: number;
  providerReference: string | null;
  failureCategory: string | null;
  requestedAt: string;
  acceptedAt: string | null;
  failedAt: string | null;
}

export interface TrackingView {
  requestId: string;
  requestStatus: string;
  trackingState: string;
  manualRoute: boolean;
  acceptedAt: string | null;
  deadlineAt: string | null;
  deadlineNote: string;
  deadlineSource: string;
  remindersDue: string[];
  nextReminderDue: string | null;
  reminderState: string;
  latestOutcome: string | null;
  latestSummary: string | null;
  latestRecordedAt: string | null;
  responseCount: number;
}

export interface ServiceResponse {
  id: string;
  requestId: string;
  outcome: string;
  summary: string;
  recordedAt: string;
}

export interface GoogleApprovalStatus {
  requestId: string | null;
  userId: string;
  userEmail: string | null;
  status: 'NONE' | 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED' | 'CONNECTED' | string;
  requestedAt: string | null;
  reviewedAt: string | null;
  rejectionReason: string | null;
  retryAvailableAt: string | null;
}

export interface GoogleApprovalQueueItem {
  requestId: string;
  userId: string;
  userEmail: string;
  status: string;
  requestedAt: string;
  reviewedAt: string | null;
  reviewedBy: string | null;
  rejectionReason: string | null;
}

// -- errors -----------------------------------------------------------------------

export interface ApiErrorBody {
  timestamp?: string;
  status?: number;
  code?: string;
  message?: string;
  path?: string;
  requestId?: string;
}
