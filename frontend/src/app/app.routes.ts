import { Routes } from '@angular/router';
import { authGuard } from './core/auth.guard';

export const routes: Routes = [
  { path: 'login', loadComponent: () => import('./pages/login.component').then(m => m.LoginComponent) },
  { path: '', canActivate: [authGuard], loadComponent: () => import('./pages/dashboard.component').then(m => m.DashboardComponent) },
  { path: 'sites/:id', canActivate: [authGuard], loadComponent: () => import('./pages/site.component').then(m => m.SiteComponent) },
  { path: 'sites/:id/plan', canActivate: [authGuard], loadComponent: () => import('./pages/planner.component').then(m => m.PlannerComponent) },
  { path: 'missions/:id', canActivate: [authGuard], loadComponent: () => import('./pages/mission.component').then(m => m.MissionComponent) },
  { path: 'assets/:id', canActivate: [authGuard], loadComponent: () => import('./pages/asset-viewer.component').then(m => m.AssetViewerComponent) },
  // Standalone test bench for the crude cloud editor (bundled test data, no auth/backend)
  { path: 'cloud-editor-demo', loadComponent: () => import('./pages/cloud-editor-demo.component').then(m => m.CloudEditorDemoComponent) },
  { path: '**', redirectTo: '' },
];
