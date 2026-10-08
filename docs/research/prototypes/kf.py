import math
k=30.63; BW=70.7
L=math.log(109.94); PLL=0.03**2; g=0.003; Pgg=0.003**2; PLg=0.0
dw=1.0; rho=0.85
# predict 7 days
L=L+g*dw; g=g*rho
F=[[1,dw],[0,rho]]
P=[[PLL,PLg],[PLg,Pgg]]
import numpy as np
F=np.array(F);P=np.array(P);Q=np.diag([0.01**2*dw,0.001**2*dw])
P=F@P@F.T+Q
print("pred M",math.exp(L),"sdL",math.sqrt(P[0,0]))
# session: 4x5 @ +22.5 rest 150, reported RIR 2,1,1,0 ; bias 0.5
def c_rest(rest,cref=0.30): return cref*min(1.6,max(0.4,(rest/120)**-0.6))
w=BW+22.5; reps=[5,5,5,5]; qrep=[2,1,1,0]; b=0.5
Fat=0; zs=[];vs=[]
for j,(r,q) in enumerate(zip(reps,qrep)):
    qt=q+b; neff=r+qt
    nfresh=neff/(1-Fat)
    z=math.log(w)+math.log(1+(nfresh-1)/k)
    sq=1.0+0.15*q
    v=(sq/(k+nfresh-1))**2+(0.005*max(0,nfresh-5))**2+(0.03**2)*(j>0)*(Fat/0.3)**2
    zs.append(z);vs.append(v)
    print(j+1,"neff",neff,"nfresh",round(nfresh,2),"M_obs",round(math.exp(z),1),"sd",round(math.sqrt(v),4))
    Fat=0.75*Fat+c_rest(150)*(min(1,r/neff))**2*(1-Fat)
W=[1/v for v in vs]; zbar=sum(z*w_ for z,w_ in zip(zs,W))/sum(W); R=0.035**2+1/sum(W)
nu=zbar-L; S=P[0,0]+R; K=P[:,0]/S
print("zbar M",math.exp(zbar),"nu",nu,"R",R,"S",S,"K",K)
Lp=L+K[0]*nu; gp=g+K[1]*nu
P=P-np.outer(K,P[0,:])
print("post M",math.exp(Lp),"added",math.exp(Lp)-BW,"sd",math.sqrt(P[0,0]),"g",gp, "80%CI", math.exp(Lp-1.28*math.sqrt(P[0,0]))-BW, math.exp(Lp+1.28*math.sqrt(P[0,0]))-BW)
