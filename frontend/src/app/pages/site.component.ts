import { Component, inject } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { DatePipe, DecimalPipe } from '@angular/common';
import { ApiService } from '../core/api.service';
import { AuthService } from '../core/auth.service';

@Component({
  standalone: true,
  imports: [RouterLink, DatePipe, DecimalPipe],
  template: `
    @if (site(); as s) {
      <h1>{{ s.name }}</h1>
      <p class="dim">{{ s.description }}</p>
      @if (auth.isStaff) {
        <a class="btn btn-primary" [routerLink]="['/sites', s.id, 'plan']">Plan new mission</a>
      }

      <h2 style="margin-top:1.5rem">Missions</h2>
      <table>
        <tr><th>Mission</th><th>State</th><th>Created</th><th></th></tr>
        @for (m of missions(); track m.id) {
          <tr>
            <td>{{ m.name }}</td>
            <td><span class="badge" [class.green]="m.state === 'COMPLETE'"
                  [class.red]="m.state === 'FAILED' || m.state === 'ABORTED'"
                  [class.amber]="m.state !== 'COMPLETE' && m.state !== 'FAILED' && m.state !== 'ABORTED'">{{ m.state }}</span></td>
            <td class="mono dim">{{ m.createdAt | date:'MMM d, HH:mm' }}</td>
            <td><a [routerLink]="['/missions', m.id]">Open</a></td>
          </tr>
        } @empty { <tr><td colspan="4" class="dim">No missions yet for this site.</td></tr> }
      </table>

      <h2 style="margin-top:1.5rem">Deliverables</h2>
      <table>
        <tr><th>Point cloud</th><th>Status</th><th>Points</th><th>CRS</th><th>Delivered</th><th></th></tr>
        @for (a of assets(); track a.id) {
          <tr>
            <td>{{ a.name }}</td>
            <td><span class="badge" [class.green]="a.status === 'READY'" [class.red]="a.status !== 'READY'">
              {{ a.status === 'READY' ? 'PUBLISHED' : 'IN REVIEW' }}</span></td>
            <td class="mono">{{ a.pointCount | number }}</td>
            <td class="mono dim">{{ a.crs }}</td>
            <td class="mono dim">{{ a.createdAt | date:'MMM d, HH:mm' }}</td>
            <td><a [routerLink]="['/assets', a.id]">Open viewer</a></td>
          </tr>
        } @empty { <tr><td colspan="6" class="dim">Finished point clouds will appear here.</td></tr> }
      </table>
    }
  `,
})
export class SiteComponent {
  auth = inject(AuthService);
  private api = inject(ApiService);
  private id = inject(ActivatedRoute).snapshot.paramMap.get('id')!;
  site = toSignal(this.api.site(this.id));
  missions = toSignal(this.api.missionsBySite(this.id), { initialValue: [] });
  assets = toSignal(this.api.siteAssets(this.id), { initialValue: [] });
}
