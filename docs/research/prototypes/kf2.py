import math
k=30.63; BW=70.7
L=math.log(109.94); PLL=0.03**2; g=0.003; Pgg=0.003**2; PLg=0.0
dw=1.0; rho=0.85
L=L+g*dw; g=g*rho
# P = F P F' + Q, F=[[1,dw],[0,rho]]
a,b_,c=PLL,PLg,Pgg
PLL2=a+2*dw*b_+dw*dw*c + 0.01**2*dw
PLg2=rho*(b_+dw*c)
Pgg2=rho*rho*c+0.001**2*dw
PLL,PLg,Pgg=PLL2,PLg2,Pgg2
print("pred M",round(math.exp(L),2),"sdL",round(math.sqrt(PLL),4))
def c_rest(rest,cref=0.30): return cref*min(1.6,max(0.4,(rest/120)**-0.6))
w=BW+22.5; reps=[5,5,5,5]; qrep=[2,1,1,0]; bias=0.5
Fat=0; zs=[];vs=[]
for j,(r,q) in enumerate(zip(reps,qrep)):
    qt=q+bias; neff=r+qt
    nfresh=neff/(1-Fat)
    z=math.log(w)+math.log(1+(nfresh-1)/k)
    sq=1.0+0.15*q
    v=(sq/(k+nfresh-1))**2+(0.005*max(0,nfresh-5))**2+(0.03**2)*(j>0)*(Fat/0.3)**2
    zs.append(z);vs.append(v)
    print(j+1,"neff",neff,"F",round(Fat,3),"nfresh",round(nfresh,2),"M_obs",round(math.exp(z),1),"sd",round(math.sqrt(v),4))
    Fat=0.75*Fat+c_rest(150)*(min(1,r/neff))**2*(1-Fat)
W=[1/v for v in vs]; zbar=sum(z*w_ for z,w_ in zip(zs,W))/sum(W); R=0.035**2+1/sum(W)
nu=zbar-L; S=PLL+R; K0=PLL/S; K1=PLg/S
print("zbar M",round(math.exp(zbar),2),"nu",round(nu,4),"R",round(R,6),"S",round(S,6),"K",round(K0,3),round(K1,4))
Lp=L+K0*nu; gp=g+K1*nu
PLLp=(1-K0)*PLL
print("post M",round(math.exp(Lp),2),"added",round(math.exp(Lp)-BW,2),"sd",round(math.sqrt(PLLp),4),"g",round(gp,5),"80%CI added",round(math.exp(Lp-1.28*math.sqrt(PLLp))-BW,1),round(math.exp(Lp+1.28*math.sqrt(PLLp))-BW,1))
