import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { UserInfo } from './models';

const KEY = 'skyscan.tokens';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private http = inject(HttpClient);
  private router = inject(Router);

  readonly user = signal<UserInfo | null>(this.restore()?.user ?? null);

  get accessToken(): string | null { return this.restore()?.accessToken ?? null; }
  get isStaff(): boolean { const r = this.user()?.role; return r === 'ADMIN' || r === 'OPERATOR'; }

  async login(username: string, password: string): Promise<void> {
    const res = await firstValueFrom(this.http.post<any>('/api/auth/login', { username, password }));
    localStorage.setItem(KEY, JSON.stringify(res));
    this.user.set(res.user);
  }

  logout(): void {
    localStorage.removeItem(KEY);
    this.user.set(null);
    this.router.navigateByUrl('/login');
  }

  private restore(): { accessToken: string; refreshToken: string; user: UserInfo } | null {
    try { return JSON.parse(localStorage.getItem(KEY) ?? 'null'); } catch { return null; }
  }
}
