/* 노고 출발 미리보기 코드 배경(11/15 명준) — 아티팩트 v195 BG 엔진과 같은 코드. 사진 · 서버 호출 없음.
 * 앱(Compose Canvas)으로 옮길 때 이 파일이 기준이다(HANDOFF_v4 7-4). 정적 화면에서는 canvas.cg-pvbg를 찾아 그린다.
 * 고르는 규칙
 *   종류: 즐겨찾기 HOME → home · OFFICE → city / 검색 결과 → fixtures/constants.json preview_bg(효성 #187):
 *         ① category_path 마지막 칸이 leaf에 있으면 그 종류 ② 아니면 윗칸 가운데 upper에 있는 칸 ③ 아니면 neutral
 *         / 시연 모드 → fixtures/effort/demo.json scene.preview_bg.kinds(#186) / 그 밖(최근 · 그 밖 즐겨찾기) → neutral(길)
 *   표의 기준은 constants.json이다. 아래 BG_LEAF · BG_UPPER는 정적 화면용 사본(값이 바뀌면 같이 맞춘다)
 *   시간대: 출발 시각(미리보기는 지금) 6~16시 day · 17~19시 dusk · 그 밖 night
 *   날씨: 출발 날씨 clear · cloud · rain · snow (rain · snow는 보이는 동안만 움직임, 동작 줄이기면 멈춤)
 *   같은 목적지 = 이름으로 씨앗(hash) → 늘 같은 모습
 * data-k = 종류, data-t = 시간대(auto = 지금 시각), data-w = 날씨, data-n = 목적지 이름 */
(function(){
const BG_LEAF={"해수욕장,해변":"sea","섬":"sea","해상국립공원":"sea","항구,포구":"sea","부두":"sea","등대":"sea","방파제":"sea","여객선터미널":"sea","방조제":"sea","해파랑길":"sea","남파랑길":"sea","서해랑길":"sea","강":"nature","계곡":"nature","산":"nature","산봉우리":"nature","오름":"nature","숲":"nature","자연휴양림":"nature","수목원,식물원":"nature","호수":"nature","저수지":"nature","폭포":"nature","연못":"nature","등산로":"nature","고개":"nature","등산정보":"nature","북한산둘레길":"nature","지리산둘레길":"nature","금정산둘레길":"nature","소백산자락길":"nature"}, BG_UPPER={"지하철,전철":"city","백화점":"city"};   /* = fixtures/constants.json preview_bg 사본 */
const kindOf=(o)=>{/* o = {nav:"HOME"|"OFFICE"|null, path:[카카오 분류, 윗칸부터], demo:시연 종류|null} — 앱 판정 함수의 참조 */
  if(o.nav==="HOME")return"home";if(o.nav==="OFFICE")return"city";if(o.demo)return o.demo;const p=o.path||[];if(!p.length)return"neutral";
  return BG_LEAF[p[p.length-1]]||p.slice(0,-1).map(c=>BG_UPPER[c]).find(Boolean)||"neutral"};
const bgTod=h=>h>=6&&h<=16?"day":h>=17&&h<=19?"dusk":"night";
const WATER=/강|호수|두물머리|계곡|저수지|댐/;
/* ── 색 ── */
const hx=c=>{c=c.replace("#","");return [parseInt(c.slice(0,2),16),parseInt(c.slice(2,4),16),parseInt(c.slice(4,6),16)]};
const toHex=a=>"#"+a.map(v=>Math.max(0,Math.min(255,Math.round(v))).toString(16).padStart(2,"0")).join("");
const mix=(a,b,t)=>{const x=hx(a),y=hx(b);return toHex(x.map((v,i)=>v+(y[i]-v)*t))};
const rgba=(c,a)=>{const x=hx(c);return `rgba(${x[0]},${x[1]},${x[2]},${a})`};
const PAL={
  day:{sky:["#5E9BD6","#9CC6EA","#E2EEF1"],sun:"#FFF7DD",far:"#A6B9CF",mid:"#8299B4",near:"#617993",trim:"#C9D7E6",win:"#E6F0F8",lit:0,road:"#5E6775",water:["#79AAD3","#4D7EA6"],sand:"#E6D6B2",ridge:["#B3C9B7","#88AB90","#5C8366","#3A5C44"],tree:"#2E4D37",star:0},
  dusk:{sky:["#2A2450","#7C4B7E","#F2A46F"],sun:"#FFD28E",far:"#4D3F69",mid:"#372D55",near:"#262040",trim:"#5A4A78",win:"#FFD49A",lit:.38,road:"#2A2340",water:["#6C4C79","#2B2447"],sand:"#6E4F62",ridge:["#735B7D","#564268","#3B2E50","#261E38"],tree:"#1E1830",star:.2},
  night:{sky:["#0D0C1C","#1F1C3E","#3B2D5A"],sun:"#F1EDDC",far:"#2B2749",mid:"#201D3B",near:"#16142B",trim:"#2F2B52",win:"#FFD8A0",lit:.46,road:"#141229",water:["#262350","#100F27"],sand:"#272140",ridge:["#2E2C51","#232141","#1A1833","#101022"],tree:"#0D0C1C",star:1}
};
function palette(tod,wx){
  const P=JSON.parse(JSON.stringify(PAL[tod]));
  const k={clear:0,cloud:tod==="day"?.28:.18,rain:tod==="day"?.42:.26,snow:tod==="day"?.22:.16}[wx];
  const to=wx==="snow"?(tod==="day"?"#D7DCE6":"#4A4E66"):(tod==="day"?"#7D8496":"#3A3B52");
  const f=c=>mix(c,to,k);
  for(const key of Object.keys(P)){const v=P[key];if(typeof v==="string")P[key]=f(v);else if(Array.isArray(v))P[key]=v.map(f)}
  if(wx==="snow"){P.road=mix(P.road,"#E8ECF3",tod==="day"?.55:.25);P.sand=mix(P.sand,"#EEF1F6",.5);P.ridge=P.ridge.map((c,i)=>mix(c,"#E9EDF3",tod==="day"?.35-i*.06:.12))}
  if(wx==="rain"||wx==="snow"||wx==="cloud") P.star=0;
  return P;
}

/* ── 씨앗 ── */
function hash(s){let h=2166136261;for(const ch of s){h^=ch.codePointAt(0);h=Math.imul(h,16777619)}return h>>>0}
function rng(seed){let a=seed||1;return()=>{a|=0;a=a+0x6D2B79F5|0;let t=Math.imul(a^a>>>15,1|a);t=t+Math.imul(t^t>>>7,61|t)^t;return((t^t>>>14)>>>0)/4294967296}}

/* ── 그리기 ── */
function glow(ctx,x,y,r,c,a){const g=ctx.createRadialGradient(x,y,0,x,y,r);g.addColorStop(0,rgba(c,a));g.addColorStop(1,rgba(c,0));ctx.fillStyle=g;ctx.beginPath();ctx.arc(x,y,r,0,Math.PI*2);ctx.fill()}
function sky(ctx,W,H,P,hor){const g=ctx.createLinearGradient(0,0,0,hor);g.addColorStop(0,P.sky[0]);g.addColorStop(.58,P.sky[1]);g.addColorStop(1,P.sky[2]);ctx.fillStyle=g;ctx.fillRect(0,0,W,H)}
function stars(ctx,W,hor,P,r,u){if(!P.star)return;const n=Math.round(W*hor/2600);for(let i=0;i<n;i++){const x=r()*W,y=r()*hor*.8;ctx.fillStyle=`rgba(255,255,255,${(.25+r()*.6)*P.star})`;const s=(r()<.08?2.2:1.1)*u;ctx.fillRect(x,y,s,s)}}
function clouds(ctx,W,hor,P,r,u,wx,tod){
  const n=wx==="clear"?(tod==="day"?3:0):wx==="cloud"?9:12; if(!n)return;
  const col=tod==="day"?(wx==="clear"?"#FFFFFF":"#E9ECF2"):(tod==="dusk"?"#C9A3B6":"#4A4766");
  for(let i=0;i<n;i++){const cx=r()*W,cy=hor*(.12+r()*.55),w=(90+r()*170)*u;
    for(let j=0;j<5;j++)glow(ctx,cx+(j-2)*w*.28,cy+(r()-.5)*18*u,w*(.32+r()*.18),col,wx==="clear"?.5:.62)}
}
function sun(ctx,x,y,u,P,tod,wx){
  if(wx==="rain"||wx==="cloud"){glow(ctx,x,y,120*u,P.sun,tod==="night"?.08:.12);return}
  if(wx==="snow"&&tod!=="night"){glow(ctx,x,y,110*u,P.sun,.18);return}
  glow(ctx,x,y,(tod==="night"?110:150)*u,P.sun,tod==="day"?.45:.35);
  ctx.fillStyle=P.sun;ctx.beginPath();ctx.arc(x,y,(tod==="night"?20:30)*u,0,Math.PI*2);ctx.fill();
}
function windows(ctx,x,y,w,h,u,P,r,lit,dayRef,style){
  const cw=style==="apt"?13*u:10*u,rh=style==="apt"?15*u:12*u;
  for(let wy=y+9*u;wy<y+h-8*u;wy+=rh)for(let wx=x+6*u;wx<x+w-6*u;wx+=cw){
    if(P.lit>0){ if(r()<lit){ctx.fillStyle=rgba(r()<.86?P.win:"#C8F04A",.45+r()*.5);ctx.fillRect(wx,wy,style==="apt"?6*u:4*u,style==="apt"?6*u:5*u)} }
    else if(dayRef&&r()<.55){ctx.fillStyle=rgba(P.trim,.55);ctx.fillRect(wx,wy,style==="apt"?6*u:4*u,style==="apt"?6*u:5*u)}
  }
}
function road(ctx,W,H,g,P,r,u,tod,x0){
  ctx.fillStyle=P.road;ctx.fillRect(0,g,W,H-g);
  if(tod==="day"){ctx.fillStyle=rgba("#FFFFFF",.35);for(let x=x0;x<W;x+=60*u)ctx.fillRect(x,g+(H-g)*.55,26*u,3*u);return}
  for(let i=0;i<70;i++){const x=x0+r()*(W-x0),y=g+3*u+r()*(H-g-6*u);glow(ctx,x,y,(4+r()*9)*u,r()<.5?"#FF5E45":"#FFE3AA",tod==="night"?.85:.6)}
  ctx.lineWidth=2.5*u;ctx.strokeStyle=rgba("#FF8A5C",.55);ctx.beginPath();ctx.moveTo(x0,H);ctx.quadraticCurveTo((x0+W)/2,g+(H-g)*.2,W,g+(H-g)*.35);ctx.stroke();
}
function city(ctx,W,H,o){
  const {P,r,u,g,x0,tod}=o;
  const layers=[[P.far,.34,.66,.12],[P.mid,.22,.62,.26],[P.near,.14,.44,.36]];
  const towerX=x0+(W-x0)*(.35+r()*.5), hasTower=r()<.6;
  layers.forEach(([c,a,b,lit],li)=>{
    let x=x0-40*u+li*14*u;
    while(x<W+20*u){const w=(36+r()*84)*u,h=(a+r()*(b-a))*g;ctx.fillStyle=c;ctx.fillRect(x,g-h,w,h+2);
      if(r()<.25){ctx.fillRect(x+w*.3,g-h-14*u,w*.4,14*u)}
      if(r()<.18){ctx.fillRect(x+w*.5-1.5*u,g-h-34*u,3*u,34*u)}
      if(li>0)windows(ctx,x,g-h,w,h,u,P,r,lit*(P.lit>0?1:0),li===2,"off");
      x+=w+(2+r()*8)*u}
    if(li===1&&hasTower){const tx=towerX,th=g*.86;ctx.fillStyle=P.mid;ctx.fillRect(tx-4*u,g-th,8*u,th);ctx.beginPath();ctx.ellipse(tx,g-th*.78,20*u,9*u,0,0,Math.PI*2);ctx.fill();if(P.lit>0)glow(ctx,tx,g-th,14*u,"#FF6A55",.9)}
  });
  if(P.lit>0)glow(ctx,(x0+W)/2,g,(W-x0)*.6,tod==="night"?"#FFB872":"#FF9F6E",tod==="night"?.16:.1);
  road(ctx,W,H,g,P,r,u,tod,x0);
}
function apartments(ctx,W,H,o){
  const {P,r,u,g,x0,tod}=o;
  [[P.far,.46,.62,.0],[P.mid,.38,.56,.5]].forEach(([c,a,b,lit],li)=>{
    let x=x0-20*u+li*36*u;
    while(x<W){const w=(84+r()*40)*u,h=(a+r()*(b-a))*g;ctx.fillStyle=c;ctx.fillRect(x,g-h,w,h+2);
      ctx.fillRect(x+w*.38,g-h-12*u,w*.24,12*u);
      if(li===1)windows(ctx,x,g-h,w,h,u,P,r,lit*(P.lit>0?1.2:0),true,"apt");
      x+=w+(26+r()*30)*u}
  });
  ctx.fillStyle=P.near;ctx.fillRect(0,g-10*u,W,H-g+10*u);
  for(let x=x0-30*u;x<W+30*u;x+=(26+r()*20)*u){const rr=(16+r()*16)*u;ctx.fillStyle=P.tree;ctx.beginPath();ctx.arc(x,g-rr*.6,rr,0,Math.PI*2);ctx.fill()}
  if(P.lit>0)for(let x=x0+40*u;x<W;x+=140*u){glow(ctx,x,g-4*u,26*u,"#FFD49A",.55);ctx.fillStyle="#FFE7BF";ctx.fillRect(x-2*u,g-6*u,4*u,4*u)}
  ctx.fillStyle=P.road;ctx.fillRect(0,g+10*u,W,H-g);
}
function sea(ctx,W,H,o){
  const {P,r,u,hor,tod,wx,sunX}=o;
  const wg=ctx.createLinearGradient(0,hor,0,H);wg.addColorStop(0,P.water[0]);wg.addColorStop(1,P.water[1]);ctx.fillStyle=wg;ctx.fillRect(0,hor,W,H-hor);
  if(r()<.75){const ix=sunX-(80+r()*260)*u,iw=(120+r()*160)*u,ih=(22+r()*36)*u;ctx.fillStyle=mix(P.water[1],P.sky[0],.4);ctx.beginPath();ctx.moveTo(ix-iw/2,hor);ctx.quadraticCurveTo(ix-iw*.15,hor-ih*1.3,ix+iw*.1,hor-ih*.6);ctx.quadraticCurveTo(ix+iw*.3,hor-ih*1.1,ix+iw/2,hor);ctx.closePath();ctx.fill()}
  if(wx==="clear"||tod==="night"&&wx!=="rain"){for(let i=0;i<30;i++){const y=hor+4*u+i*3.6*u,w=(50+r()*150-i*2.5)*u;if(w<4)continue;ctx.fillStyle=rgba(P.sun,Math.max(0,.6-i*.019)*(wx==="clear"?1:.4));ctx.fillRect(sunX-w/2+(r()-.5)*24*u,y,w,2*u)}}
  ctx.strokeStyle=rgba("#FFFFFF",tod==="day"?.35:.12);ctx.lineWidth=1.5*u;
  for(let i=0;i<26;i++){const y=hor+(H-hor)*(.15+r()*.8),x=r()*W,w=(20+r()*60)*u;ctx.beginPath();ctx.moveTo(x,y);ctx.quadraticCurveTo(x+w/2,y-3*u,x+w,y);ctx.stroke()}
  if(o.beach){const sy=hor+(H-hor)*.62;ctx.fillStyle=P.sand;ctx.beginPath();ctx.moveTo(0,H);ctx.lineTo(0,sy+12*u);ctx.bezierCurveTo(W*.3,sy-10*u,W*.6,sy+18*u,W,sy-4*u);ctx.lineTo(W,H);ctx.fill();
    ctx.strokeStyle=rgba("#FFFFFF",tod==="day"?.6:.2);ctx.lineWidth=2*u;ctx.beginPath();ctx.moveTo(0,sy+12*u);ctx.bezierCurveTo(W*.3,sy-10*u,W*.6,sy+18*u,W,sy-4*u);ctx.stroke()}
}
function ridge(ctx,W,H,base,amp,c,r,u){const p1=r()*6,p2=r()*6,f1=(90+r()*80)*u,f2=(34+r()*22)*u;ctx.fillStyle=c;ctx.beginPath();ctx.moveTo(0,H);for(let x=0;x<=W+10;x+=8*u){ctx.lineTo(x,base+Math.sin(x/f1+p1)*amp+Math.sin(x/f2+p2)*amp*.32)}ctx.lineTo(W,H);ctx.closePath();ctx.fill()}
function nature(ctx,W,H,o){
  const {P,r,u,g,hor,tod,water,name}=o;
  const span=g-hor;
  ridge(ctx,W,H,hor+span*.05,26*u,P.ridge[0],r,u);
  ridge(ctx,W,H,hor+span*.28,22*u,P.ridge[1],r,u);
  if(water){const wy=hor+span*.62;const wg=ctx.createLinearGradient(0,wy,0,H);wg.addColorStop(0,mix(P.sky[2],P.water[0],.4));wg.addColorStop(1,P.water[1]);ctx.fillStyle=wg;ctx.fillRect(0,wy,W,H-wy);
    ctx.strokeStyle=rgba("#FFFFFF",tod==="day"?.3:.12);ctx.lineWidth=1.5*u;for(let i=0;i<18;i++){const y=wy+(H-wy)*r(),x=r()*W,w=(30+r()*80)*u;ctx.beginPath();ctx.moveTo(x,y);ctx.lineTo(x+w,y);ctx.stroke()}
    ridge(ctx,W,H,hor+span*.5,14*u,P.ridge[2],r,u);
    ctx.fillStyle=P.water[1];ctx.fillRect(0,hor+span*.5+18*u,W,0);
    const lx=W*(.66+r()*.12),ly=hor+span*.6;
    if(/두물머리|느티/.test(name)||r()<.5){ctx.fillStyle=P.tree;ctx.fillRect(lx-4*u,ly-46*u,8*u,46*u);ctx.beginPath();ctx.ellipse(lx,ly-62*u,58*u,34*u,0,0,Math.PI*2);ctx.fill()}
    ctx.fillStyle=P.ridge[3];ctx.beginPath();ctx.moveTo(0,H);ctx.lineTo(0,g+8*u);ctx.quadraticCurveTo(W*.25,g-6*u,W*.45,g+14*u);ctx.lineTo(W*.45,H);ctx.fill();
  }else{
    ridge(ctx,W,H,hor+span*.55,18*u,P.ridge[2],r,u);
    ridge(ctx,W,H,g-span*.02,12*u,P.ridge[3],r,u);
    for(let i=0;i<70;i++){const x=r()*W,y=g-span*.02+(r()*.6)*span*.5,s=(10+r()*14)*u;ctx.fillStyle=P.tree;ctx.beginPath();ctx.moveTo(x,y-s*1.8);ctx.lineTo(x+s*.55,y);ctx.lineTo(x-s*.55,y);ctx.closePath();ctx.fill()}
  }
}
function neutral(ctx,W,H,o){
  const {P,r,u,hor,tod,variant,land}=o;
  const accent=tod==="night"?"#C8F04A":tod==="dusk"?"#FFB37A":"#FFFFFF";
  if(variant==="road"){
    const vx=land?W*.6:W*.5, vy=hor;
    const gg=ctx.createLinearGradient(0,vy,0,H);gg.addColorStop(0,mix(P.road,P.sky[2],.45));gg.addColorStop(1,P.road);ctx.fillStyle=gg;ctx.fillRect(0,vy,W,H-vy);
    glow(ctx,vx,vy,(land?420:300)*u,accent,tod==="day"?.35:.22);
    const bw=land?W*1.05:W*1.6;
    ctx.fillStyle=mix(P.road,"#0B0A16",.18);ctx.beginPath();ctx.moveTo(vx-4*u,vy);ctx.lineTo(vx+4*u,vy);ctx.lineTo(vx+bw/2,H);ctx.lineTo(vx-bw/2,H);ctx.closePath();ctx.fill();
    ctx.strokeStyle=rgba("#FFFFFF",tod==="day"?.6:.32);ctx.lineWidth=3*u;
    for(const sx of [-1,1]){ctx.beginPath();ctx.moveTo(vx+sx*4*u,vy);ctx.lineTo(vx+sx*bw/2*.92,H);ctx.stroke()}
    for(let i=0;i<9;i++){const t0=Math.pow(i/9,1.9),t1=Math.pow((i+.45)/9,1.9);const y0=vy+(H-vy)*t0,y1=vy+(H-vy)*t1;ctx.strokeStyle=rgba(tod==="night"?"#C8F04A":"#FFFFFF",tod==="day"?.75:.5);ctx.lineWidth=(1+t1*5)*u;ctx.beginPath();ctx.moveTo(vx,y0);ctx.lineTo(vx,y1);ctx.stroke()}
    if(tod!=="day"){for(let i=0;i<5;i++){const t=.08+r()*.5,y=vy+(H-vy)*t,dx=(H-vy)*t*(.18+r()*.1),sz=(2+t*9)*u;glow(ctx,vx-dx,y,sz*2.4,"#FF5E45",.8);glow(ctx,vx-dx+sz*2.2,y,sz*2.4,"#FF5E45",.8)}}
    ctx.fillStyle=rgba(mix(P.far,P.sky[2],.3),.9);ctx.beginPath();ctx.moveTo(0,vy);for(let x=0;x<=W;x+=24*u)ctx.lineTo(x,vy-(6+Math.sin(x/(120*u))*5+Math.sin(x/(47*u))*3)*u);ctx.lineTo(W,vy);ctx.closePath();ctx.fill();
  }
  if(variant==="bokeh"){
    const cols=tod==="night"?["#FFC37A","#FF6A55","#C8F04A","#FFFFFF","#8FB4FF"]:tod==="dusk"?["#FFB37A","#FF8FA3","#FFE1B0","#C8F04A"]:["#FFFFFF","#E6F2FF","#FFF6D8","#DDF59A"];
    const n=land?46:34;
    for(let i=0;i<n;i++){const x=(land?W*.28:0)+r()*(land?W*.74:W),y=H*(.12+r()*.8),rad=(14+Math.pow(r(),2)*80)*u,c=cols[Math.floor(r()*cols.length)];
      glow(ctx,x,y,rad,c,(tod==="day"?.35:.5)*(.5+r()*.5));ctx.strokeStyle=rgba(c,tod==="day"?.18:.14);ctx.lineWidth=1.2*u;ctx.beginPath();ctx.arc(x,y,rad*.62,0,Math.PI*2);ctx.stroke()}
    for(let i=0;i<4;i++){const y=H*(.55+r()*.35);const g2=ctx.createLinearGradient(land?W*.3:0,0,W,0);g2.addColorStop(0,rgba(accent,0));g2.addColorStop(.6,rgba(accent,tod==="day"?.22:.16));g2.addColorStop(1,rgba(accent,0));ctx.fillStyle=g2;ctx.fillRect(0,y,W,(2+r()*3)*u)}
  }
  if(variant==="route"){
    ctx.lineWidth=1.3*u;
    const cx=land?W*.72:W*.55,cy=land?H*.55:H*.4;
    for(let k=1;k<16;k++){ctx.strokeStyle=rgba(tod==="day"?"#FFFFFF":"#B9B3D9",tod==="day"?.16:.09);ctx.beginPath();
      for(let a=0;a<=Math.PI*2+.01;a+=.08){const rr=k*28*u*(1+.18*Math.sin(a*3+k*.7)+.08*Math.sin(a*5+k));const x=cx+Math.cos(a)*rr*1.7,y=cy+Math.sin(a)*rr;a===0?ctx.moveTo(x,y):ctx.lineTo(x,y)}ctx.stroke()}
    const sx=land?W*.34:W*.1,sy=land?H*.72:H*.62,ex=land?W*.9:W*.88,ey=land?H*.26:H*.16;
    const c1x=sx+(ex-sx)*.35,c1y=sy-(sy-ey)*.05-60*u*r(),c2x=sx+(ex-sx)*.62,c2y=ey+(sy-ey)*.75;
    ctx.strokeStyle=rgba("#C8F04A",.22);ctx.lineWidth=14*u;ctx.lineCap="round";ctx.beginPath();ctx.moveTo(sx,sy);ctx.bezierCurveTo(c1x,c1y,c2x,c2y,ex,ey);ctx.stroke();
    ctx.strokeStyle=rgba("#C8F04A",.95);ctx.lineWidth=5*u;ctx.setLineDash([2*u,14*u]);ctx.beginPath();ctx.moveTo(sx,sy);ctx.bezierCurveTo(c1x,c1y,c2x,c2y,ex,ey);ctx.stroke();ctx.setLineDash([]);
    ctx.lineWidth=4*u;ctx.strokeStyle="#C8F04A";ctx.fillStyle=P.sky[0];ctx.beginPath();ctx.arc(sx,sy,11*u,0,Math.PI*2);ctx.fill();ctx.stroke();
    glow(ctx,ex,ey,46*u,"#C8F04A",.35);ctx.fillStyle="#C8F04A";ctx.beginPath();ctx.arc(ex,ey,13*u,0,Math.PI*2);ctx.fill();ctx.fillStyle=P.sky[0];ctx.beginPath();ctx.arc(ex,ey,5*u,0,Math.PI*2);ctx.fill();
    ctx.lineCap="butt";
  }
}
function scene(ctx,W,H,st,layout){
  const P=palette(st.tod,st.wx), r=rng(hash(st.name+"|"+st.type)), u=H/478;
  const land=layout==="land";
  const x0=land?W*.26:0;
  const hor=st.type==="sea"?(land?H*.64:H*.44):(land?H*.5:H*.3);
  const g=land?H*.88:H*.58;
  const sunX=land?W*(.5+r()*.16):W*(.6+r()*.25);   /* 가로: 글자와 [안내 시작] 사이 */
  const sunY=st.tod==="day"?hor*(.32+r()*.18):st.tod==="dusk"?hor-18*u:hor*(.25+r()*.2);
  const neu=st.type==="neutral", nv=st.nv||"road";
  const nhor=neu?(nv==="road"?(land?H*.56:H*.42):H):null;
  sky(ctx,W,H,P,neu?nhor:(st.type==="sea"?hor:g));
  stars(ctx,W,neu?(nv==="road"?nhor:H*.9):hor,P,r,u);
  if(!neu||nv==="road"){sun(ctx,neu?(land?W*.6:W*.5):sunX,neu?nhor-(st.tod==="day"?90:st.tod==="dusk"?14:110)*u:sunY,u,P,st.tod,st.wx);clouds(ctx,W,neu?nhor:hor,P,r,u,st.wx,st.tod)}
  const o={P,r,u,g,hor:neu?nhor:hor,x0,tod:st.tod,wx:st.wx,sunX,name:st.name,water:WATER.test(st.name),beach:/해수욕장|해변|비치|꽃지/.test(st.name),variant:nv,land};
  ({neutral,city,home:apartments,sea,nature})[st.type](ctx,W,H,o);
  if(st.wx==="rain"||st.wx==="cloud"){ctx.fillStyle=rgba("#20203A",st.wx==="rain"?.14:.06);ctx.fillRect(0,0,W,H)}
}
function precip(ctx,W,H,wx,t,seed){
  if(wx!=="rain"&&wx!=="snow")return;
  const r=rng(seed),u=H/478,n=Math.round(W*H/(wx==="rain"?5200:7800));
  for(let i=0;i<n;i++){
    const bx=r()*W,by=r()*H,sp=wx==="rain"?(900+r()*500)*u:(40+r()*50)*u;
    if(wx==="rain"){const y=(by+t*sp)%(H+60*u)-30*u,x=(bx-t*sp*.22)%W,L=(18+r()*20)*u;const xx=x<0?x+W:x;
      ctx.strokeStyle=`rgba(220,230,255,${.18+r()*.28})`;ctx.lineWidth=1.4*u;ctx.beginPath();ctx.moveTo(xx,y);ctx.lineTo(xx-L*.22,y+L);ctx.stroke()}
    else{const y=(by+t*sp)%(H+10*u),x=bx+Math.sin(t*1.3+i)*8*u,s=(1.5+r()*2.6)*u;ctx.fillStyle=`rgba(255,255,255,${.55+r()*.4})`;ctx.beginPath();ctx.arc(x,y,s,0,Math.PI*2);ctx.fill()}
  }
}

const live=new Set(),RM=matchMedia("(prefers-reduced-motion: reduce)");
function draw(root){(root||document).querySelectorAll("canvas.cg-pvbg,canvas.pvbg").forEach(cv=>{if(cv.dataset.done)return;const box=cv.parentElement,W=box.offsetWidth,H=box.offsetHeight;if(!W||!H)return;cv.dataset.done="1";
  const r=box.getBoundingClientRect(),k=Math.max(.25,Math.min(1,(r.width/W||1)*(window.devicePixelRatio||1)));cv.width=Math.round(W*k);cv.height=Math.round(H*k);
  const t=cv.dataset.t==="auto"||!cv.dataset.t?bgTod(new Date().getHours()):cv.dataset.t;
  const st={name:cv.dataset.n||"",type:cv.dataset.k||"neutral",tod:t,wx:cv.dataset.w||"clear",nv:"road"},layout=box.closest(".cg-p,.p")?"port":"land";
  const base=document.createElement("canvas");base.width=cv.width;base.height=cv.height;const bx=base.getContext("2d");bx.scale(k,k);scene(bx,W,H,st,layout);
  const ctx=cv.getContext("2d"),seed=hash(st.name)+7,f=tt=>{ctx.setTransform(1,0,0,1,0,0);ctx.drawImage(base,0,0);ctx.setTransform(k,0,0,k,0,0);precip(ctx,W,H,st.wx,tt,seed)};f(0);
  if(st.wx==="rain"||st.wx==="snow")live.add({cv,f})})}
const t0=performance.now();(function loop(now){if(!RM.matches&&!document.hidden){const tt=(now-t0)/1000,vh=innerHeight;for(const e of live){if(!e.cv.isConnected){live.delete(e);continue}const r=e.cv.getBoundingClientRect();if(r.bottom>0&&r.top<vh)e.f(tt)}}requestAnimationFrame(loop)})(t0);
const start=()=>{draw(document);new MutationObserver(()=>draw(document)).observe(document.body,{childList:true,subtree:true})};
document.readyState==="loading"?document.addEventListener("DOMContentLoaded",start):start();
window.NogoPreviewBg={scene,precip,hash,draw,kindOf,BG_LEAF,BG_UPPER,bgTod};
})();
