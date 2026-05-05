import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    loadComponent: () => import('./pages/dashboard/dashboard.page').then((m) => m.DashboardPage),
    title: 'Dashboard · LevelSweep',
  },
  {
    path: 'journal',
    loadComponent: () => import('./pages/journal/journal.page').then((m) => m.JournalPage),
    title: 'Journal · LevelSweep',
  },
  {
    path: 'narratives',
    loadComponent: () => import('./pages/narratives/narratives.page').then((m) => m.NarrativesPage),
    title: 'Narratives · LevelSweep',
  },
  {
    path: 'reviews',
    loadComponent: () => import('./pages/reviews/reviews.page').then((m) => m.ReviewsPage),
    title: 'Reviews · LevelSweep',
  },
  {
    path: 'projections',
    loadComponent: () => import('./pages/projections/projections.page').then((m) => m.ProjectionsPage),
    title: 'Projections · LevelSweep',
  },
  {
    path: 'calendar',
    loadComponent: () => import('./pages/calendar/calendar.page').then((m) => m.CalendarPage),
    title: 'Calendar · LevelSweep',
  },
  {
    path: 'assistant',
    loadComponent: () => import('./pages/assistant/assistant.page').then((m) => m.AssistantPage),
    title: 'Assistant · LevelSweep',
  },
  { path: '**', redirectTo: '' },
];
