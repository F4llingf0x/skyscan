import { Component, OnDestroy, OnInit, inject, signal, computed } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DecimalPipe } from '@angular/common';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../core/api.service';
import { AuthService } from '../core/auth.service';

interface Check { component: string; status: string; value: string; message: string; hint?: string; }
interface HoverStatus {
  state: string; aglM: number; targetM: number; thrust?: number;
  rollDeg?: number; pitchDeg?: number; endsInS?: number;
  nearest?: { az: number; dist: number; closing: number } | null;
}

/**
 * Pre-flight drone check page — everything you run BEFORE trusting a mission:
 *  1. Remote self-test (props OFF): the on-vehicle diagnostic suite.
 *  2. Hover & dodge test (props ON, GPS-FREE): rise to a set height on the
 *     rangefinder, hold, and dodge toward-moving obstacles. Works indoors /
 *     without GPS — at 1 m for a minute, drift is irrelevant; attitude comes
 *     from the IMU, height from the rangefinder, proximity from the lidar.
 */
@Component({
  selector: 'app-drone-check',
  standalone: true,
  imports: [FormsModule, DecimalPipe],
  template: `
    <div class="page">
      <h1>Drone check <span class="badge amber">pre-flight</span></h1>
      <p class="dim">Run these before a mission: the self-test with props off,
        then — in a clear, netted area — the hover &amp; dodge test with props on.</p>

      <div class="cols">
        <!-- ============================ self-test ============================ -->
        <div class="card">
          <h2>Self-test <span class="badge cyan">props off</span></h2>
          <button (click)="runSelfTest()" [disabled]="testRunning()">
            {{ testRunning() ? 'Running on drone…' : 'Run self-test' }}
          </button>
          @if (selfTest(); as st) {
            <p class="mono" style="margin:.6rem 0 .3rem">
              Overall:
              <span class="badge" [class.green]="st.overall === 'PASS'"
                    [class.amber]="st.overall === 'WARN'" [class.red]="st.overall === 'FAIL'">
                {{ st.overall }}</span>
              <span class="dim"> · {{ st.mode }} · {{ st.checks.length }} checks</span>
            </p>
            <div class="checks">
              @for (c of st.checks; track c.component) {
                <div class="check" [class.bad]="c.status === 'FAIL'">
                  <span class="badge" [class.green]="c.status === 'PASS'"
                        [class.amber]="c.status === 'WARN'" [class.red]="c.status === 'FAIL'">
                    {{ c.status }}</span>
                  <span class="mono comp">{{ c.component }}</span>
                  <span class="mono dim val">{{ c.value }}</span>
                  <div class="msg">{{ c.message }}
                    @if (c.hint) { <span class="dim"> — {{ c.hint }}</span> }
                  </div>
                </div>
              }
            </div>
          } @else {
            <p class="dim">No report yet — run the self-test.</p>
          }
        </div>

        <!-- ======================== hover & dodge test ======================= -->
        <div class="card">
          <h2>Hover &amp; dodge test <span class="badge red">props ON</span></h2>
          <div class="warn mono">
            ⚠ The drone will ARM and FLY. Clear, ideally netted area; prop guards
            strongly recommended. Approach slowly from a covered side to trigger a
            dodge — it tilts away from anything moving toward it, and lands
            immediately if you get inside {{ 0.6 }} m. No GPS needed.
          </div>
          <div class="fields">
            <div class="field"><label>Height (m, max 3)</label>
              <input type="number" step="0.1" min="0.3" max="3" [(ngModel)]="heightM" name="h"></div>
            <div class="field"><label>Duration (s, max 180)</label>
              <input type="number" step="5" min="10" max="180" [(ngModel)]="durationS" name="d"></div>
          </div>
          <div class="btns">
            <button class="btn-primary" (click)="startHover()" [disabled]="hoverActive()">
              {{ hoverActive() ? 'Test running…' : 'Start hover test' }}</button>
            <button class="danger" (click)="abortHover()" [disabled]="!hoverActive()">
              Abort — land now</button>
          </div>
          <p class="mono dim">Sensor coverage depends on the lidar mount: vertical ring =
            front + sides (rear is blind); horizontal ring = all directions.</p>

          @if (hover(); as h) {
            <div class="live">
              <div class="strip mono">
                <span class="badge" [class.green]="h.state === 'HOLD'"
                      [class.amber]="h.state === 'CLIMB' || h.state === 'LANDING'"
                      [class.red]="h.state === 'DODGE'">{{ h.state }}</span>
                <span>AGL <b>{{ h.aglM | number:'1.2-2' }} m</b> / {{ h.targetM | number:'1.1-1' }} m</span>
                @if (h.nearest) {
                  <span [class.hot]="h.nearest.closing > 0">
                    obstacle {{ h.nearest.dist | number:'1.1-1' }} m &#64; {{ h.nearest.az | number:'1.0-0' }}°
                    @if (h.nearest.closing > 0) { — closing {{ h.nearest.closing | number:'1.1-1' }} m/s }
                  </span>
                }
                @if (h.endsInS != null) { <span class="dim">{{ h.endsInS | number:'1.0-0' }} s left</span> }
              </div>
              <div class="bar"><div class="fill" [style.width.%]="100 * h.aglM / h.targetM"></div></div>
            </div>
          }

          <div class="log mono">
            @for (line of log(); track $index) { <div>{{ line }}</div> }
          </div>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .page { max-width: 1100px; margin: 0 auto; padding: 1rem; }
    .cols { display: grid; grid-template-columns: 1fr 1fr; gap: 1rem; align-items: start; }
    @media (max-width: 900px) { .cols { grid-template-columns: 1fr; } }
    .warn { background: #3a1414; border: 1px solid #b3261e; border-radius: 8px;
            padding: .6rem .8rem; margin: .5rem 0; }
    .fields { display: flex; gap: 1rem; }
    .btns { display: flex; gap: .6rem; margin: .5rem 0; }
    .danger { background: #b3261e; color: #fff; }
    .checks { max-height: 420px; overflow: auto; margin-top: .4rem; }
    .check { display: grid; grid-template-columns: auto auto 1fr; gap: .3rem .6rem;
             padding: .3rem 0; border-bottom: 1px solid var(--border); }
    .check .msg { grid-column: 1 / 4; font-size: .82rem; }
    .comp { font-weight: 600; }
    .strip { display: flex; gap: 1rem; align-items: center; flex-wrap: wrap; margin: .5rem 0 .3rem; }
    .hot { color: #ff6b5e; font-weight: 600; }
    .bar { height: 8px; background: var(--border); border-radius: 4px; overflow: hidden; }
    .fill { height: 100%; background: var(--accent, #5cc8ff); transition: width .3s; }
    .log { max-height: 200px; overflow: auto; margin-top: .6rem; font-size: .78rem;
           color: var(--dim); border-top: 1px solid var(--border); padding-top: .4rem; }
  `],
})
export class DroneCheckComponent implements OnInit, OnDestroy {
  api = inject(ApiService);
  auth = inject(AuthService);
  private es?: EventSource;

  selfTest = signal<{ overall: string; mode: string; checks: Check[] } | null>(null);
  testRunning = signal(false);
  hover = signal<HoverStatus | null>(null);
  hoverActive = signal(false);
  log = signal<string[]>([]);
  heightM = 1.0;
  durationS = 60;

  async ngOnInit() {
    try {
      const r = await firstValueFrom(this.api.droneSelfTestLatest());
      if (r.report) this.selfTest.set(JSON.parse(r.report));
    } catch { /* none yet */ }

    this.es = this.api.droneEvents();
    this.es.addEventListener('selftest', (e: MessageEvent) => {
      try { this.selfTest.set(JSON.parse(JSON.parse(e.data))); } catch {
        try { this.selfTest.set(JSON.parse(e.data)); } catch { /* ignore */ }
      }
      this.testRunning.set(false);
    });
    this.es.addEventListener('hover_status', (e: MessageEvent) => {
      try {
        const s = typeof e.data === 'string' ? JSON.parse(e.data) : e.data;
        this.hover.set(typeof s === 'string' ? JSON.parse(s) : s);
        this.hoverActive.set(true);
      } catch { /* ignore */ }
    });
    this.es.addEventListener('hover_event', (e: MessageEvent) => {
      try {
        const raw = typeof e.data === 'string' ? JSON.parse(e.data) : e.data;
        const p = typeof raw === 'string' ? JSON.parse(raw) : raw;
        this.log.set([`${new Date().toLocaleTimeString()}  ${p.event}` +
          (p.azimuthDeg != null ? ` @ ${p.azimuthDeg}° ${p.distM} m` : '') +
          (p.reason ? ` (${p.reason})` : ''), ...this.log()].slice(0, 60));
        if (['DONE', 'ABORTED', 'FAILED'].includes(p.event)) this.hoverActive.set(false);
        if (p.event === 'PREFLIGHT') this.hoverActive.set(true);
      } catch { /* ignore */ }
    });
  }

  ngOnDestroy() { this.es?.close(); }

  runSelfTest() {
    this.testRunning.set(true);
    this.api.runDroneSelfTest().subscribe({ error: () => this.testRunning.set(false) });
    setTimeout(() => this.testRunning.set(false), 30000);   // failsafe UI unlock
  }

  startHover() {
    this.log.set([]);
    this.api.startHoverTest({ heightM: this.heightM, durationS: this.durationS })
      .subscribe({ next: () => this.hoverActive.set(true) });
  }

  abortHover() { this.api.abortHoverTest().subscribe(); }
}
