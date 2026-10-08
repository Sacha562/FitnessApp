import math
def cap(w,M,k): return 1+k*(M/w-1)
def c_rest(rest,cref=0.30): return cref*min(1.6,max(0.4,(rest/120)**-0.6))
def sim(w,M,k,S,rest,q,rlo,rhi,fixed=None,gamma=2.0,d=0.75):
    F=0; out=[]
    cf=cap(w,M,k)
    for j in range(S):
        cj=cf*(1-F)
        if fixed: r=fixed
        else:
            r=math.floor(cj-q+0.5-1e-9); r=max(rlo,min(rhi,r))
        out.append((r,round(cj,2),round(cj-r,2)))
        F=d*F+c_rest(rest)*(min(1,r/cj))**gamma*(1-F)
    return out
def J(plan,q,rlo,rhi):
    s=0
    for r,c,rir in plan:
        s+=(rir-q)**2+4*max(0,(q-0.75)-rir)**2
        if rir<0: s+=100
    return s
BW=70.7
# k fit two points
k=(70.7*17-103.2*2)/(103.2-70.7); M=103.2*(1+2/k)
print("k",k,"M",M,"added",M-BW)
# alt with 2% strength gain
k2=(70.7*17-0.98*103.2*2)/(0.98*103.2-70.7); print("k with 2% gain",k2)
# formulas at 3 reps
w=103.2;r=3
f={'Epley':w*(1+r/30),'Brzycki':w*36/(37-r),'Lombardi':w*r**0.1,'Mayhew':100*w/(52.2+41.9*math.exp(-0.055*r)),'Wathen':100*w/(48.8+53.8*math.exp(-0.075*r)),"OConner":w*(1+0.025*r),'Lander':100*w/(101.3-2.67123*r),'Tuchscherer':w/0.922,'personal':M}
for n,v in f.items(): print(n,round(v,1),round(v-BW,1))
print("anchor 4x5 @20 rest150", sim(90.7,M,k,4,150,1,5,5,fixed=5))
for add in [25,26.25,27.5,28.75,30]:
    p=sim(BW+add,M,k,6,180,2,2,2,fixed=2); print("6x2 +",add,p)
for wlp in [115,120,125,130,135]:
    p=sim(wlp,158.7,45,3,150,2,8,10); print("LP",wlp,p,round(J(p,2,8,10),2))
