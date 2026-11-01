import { Routes } from '@angular/router';
import { PendingListComponent } from './pending-list.component';
import { UpdateDetailComponent } from './update-detail.component';

export const routes: Routes = [
  { path: '', component: PendingListComponent },
  { path: 'ef/:id', component: UpdateDetailComponent },
  { path: '**', redirectTo: '' },
];
