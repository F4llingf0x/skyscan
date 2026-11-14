import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '../core/auth.service';

@Component({
  standalone: true,
  imports: [FormsModule],
  template: `
    <div class="wrap">
      <div class="card panel">
        <h1>Sign in</h1>
        <p class="dim">Operator console &amp; customer portal</p>
        <form (ngSubmit)="submit()">
          <div class="field"><label for="u">Username</label>
            <input id="u" name="u" [(ngModel)]="username" autocomplete="username" required></div>
          <div class="field"><label for="p">Password</label>
            <input id="p" name="p" type="password" [(ngModel)]="password" autocomplete="current-password" required></div>
          @if (error()) { <p class="mono" style="color: var(--red)">{{ error() }}</p> }
          <button class="btn-primary" type="submit" [disabled]="busy()">Sign in</button>
        </form>
        <p class="dim mono demo">demo accounts — operator / customer / admin · password: password</p>
      </div>
    </div>
  `,
  styles: [`
    .wrap { display: grid; place-items: center; min-height: 70vh; }
    .panel { width: 360px; }
    .demo { margin-top: 1.2rem; font-size: .72rem; }
  `],
})
export class LoginComponent {
  private auth = inject(AuthService);
  private router = inject(Router);
  username = '';
  password = '';
  busy = signal(false);
  error = signal('');

  async submit() {
    this.busy.set(true); this.error.set('');
    try {
      await this.auth.login(this.username, this.password);
      this.router.navigateByUrl('/');
    } catch {
      this.error.set('Sign-in failed. Check username and password.');
    } finally { this.busy.set(false); }
  }
}
