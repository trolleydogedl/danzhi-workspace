"""Read-only inspection: never mutates or emits an APK/DEX."""
from pathlib import Path
import struct, zipfile, hashlib, json, argparse

def uleb(b,p):
    v=s=0
    while True:
        c=b[p];p+=1;v|=(c&127)<<s;s+=7
        if c<128:return v,p

class Dex:
    def __init__(self,b):
        self.b=b; self.codes={}
        def table(off):return struct.unpack_from('<II',b,off)
        n,o=table(56);self.strings=[]
        for i in range(n):
            q=struct.unpack_from('<I',b,o+4*i)[0];_,q=uleb(b,q)
            self.strings.append(b[q:b.index(b'\0',q)].decode('utf8','replace'))
        n,o=table(64);self.types=[self.strings[struct.unpack_from('<I',b,o+4*i)[0]] for i in range(n)]
        n,o=table(88);self.methods=[]
        for i in range(n):
            c,p,s=struct.unpack_from('<HHI',b,o+8*i);self.methods.append(self.types[c]+'->'+self.strings[s])
        n,o=table(96)
        for i in range(n):
            vals=struct.unpack_from('<8I',b,o+32*i);p=vals[6]
            if not p:continue
            sizes=[]
            for k in range(4):v,p=uleb(b,p);sizes.append(v)
            for k in range(sizes[0]+sizes[1]):_,p=uleb(b,p);_,p=uleb(b,p)
            for nm in sizes[2:]:
                idx=0
                for j in range(nm):
                    diff,p=uleb(b,p);idx+=diff;_,p=uleb(b,p);co,p=uleb(b,p)
                    if co:self.codes[self.methods[idx]]=co
    def code(self,name):
        o=self.codes[name];reg,ins,outs,tr,dbg,n=struct.unpack_from('<HHHHII',self.b,o)
        return dict(offset=o,registers=reg,ins=ins,outs=outs,tries=tr,units=n,hex=self.b[o+16:o+16+n*2].hex())

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk_directory',type=Path,help='Directory containing danzhi-1.6.5.apk through danzhi-1.6.9.apk')
    args=parser.parse_args()
    out={}
    ds={}
    for v in ['1.6.5','1.6.6','1.6.7','1.6.8','1.6.9']:
        p=args.apk_directory/('danzhi-'+v+'.apk')
        with zipfile.ZipFile(p) as z:
            bs=z.read('classes.dex');ds[v]=Dex(bs)
            out[v]={'apk_sha256':hashlib.sha256(p.read_bytes()).hexdigest(),
                    'dex_sha256':{n:hashlib.sha256(z.read(n)).hexdigest() for n in z.namelist() if n.endswith('.dex')},
                    'code':{n:ds[v].code(n) for n in ds[v].codes if 'FudanClient;->enterService' in n}}
    base=ds['1.6.7'];new=ds['1.6.8']
    diffs=[i for i,(a,b) in enumerate(zip(base.b,new.b)) if a!=b]
    out['diff_167_168']=[{'offset':i,'before':base.b[i],'after':new.b[i], 'method':next((n for n,o in base.codes.items() if o+16<=i<o+16+base.code(n)['units']*2),None)} for i in diffs]
    print(json.dumps(out,indent=2,ensure_ascii=False))
if __name__=='__main__':main()
