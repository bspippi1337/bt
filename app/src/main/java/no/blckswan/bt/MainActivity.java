package no.blckswan.bt;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.*;
import android.util.Base64;
import android.view.View;
import android.widget.*;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class MainActivity extends Activity {
    private final Handler h = new Handler(Looper.getMainLooper());
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private final LinkedHashMap<String,BluetoothDevice> found = new LinkedHashMap<>();
    private final LinkedHashMap<String,BluetoothGattCharacteristic> writable = new LinkedHashMap<>();
    private ArrayAdapter<String> deviceAdapter, charAdapter;
    private Spinner deviceSpin, charSpin;
    private TextView log, map;
    private EditText rename, prefix, body, suffix, seed, count;
    private CheckBox arm;
    private final ArrayDeque<WriteCase> queue = new ArrayDeque<>();
    private WriteCase current;
    private BluetoothGattCharacteristic nameChar;
    private BluetoothGattCharacteristic fuzzTarget;
    private boolean scanning;

    static class WriteCase {
        final String id, kind; final long seed; final byte[] data;
        WriteCase(String id,String kind,long seed,byte[]data){this.id=id;this.kind=kind;this.seed=seed;this.data=data;}
    }

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        adapter=((BluetoothManager)getSystemService(BLUETOOTH_SERVICE)).getAdapter();
        buildUi(); requestBt();
    }

    private void buildUi(){
        ScrollView sv=new ScrollView(this); LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(18,18,18,50); root.setBackgroundColor(Color.rgb(8,11,14)); sv.addView(root);
        TextView title=t("BT",42); title.setTextColor(Color.rgb(255,216,74)); root.addView(title);
        root.addView(t("BLE lab // scan · map · graffiti · frame lab · scientific body fuzz",16));

        section(root,"Bluetooth scanner");
        deviceSpin=new Spinner(this); root.addView(deviceSpin); deviceAdapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new ArrayList<>()); deviceSpin.setAdapter(deviceAdapter);
        row(root,btn("SCAN",v->startScan()),btn("STOP",v->stopScan()),btn("CONNECT",v->connectSelected()),btn("DISCONNECT",v->disconnect()));
        map=mono("Ingen GATT-data ennå."); root.addView(map);

        section(root,"Graffiti");
        root.addView(t("Persistent rename først. TAG bruker bare en characteristic du selv har valgt fra den oppdagede writable-listen.",14));
        rename=field("Nytt persistent Bluetooth-navn"); root.addView(rename);
        row(root,btn("RENAME PLAN",v->renamePlan()),btn("WRITE 0x2A00",v->doRename()));

        section(root,"Frame lab: prefix + mutable body + suffix");
        charSpin=new Spinner(this); root.addView(charSpin); charAdapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new ArrayList<>()); charSpin.setAdapter(charAdapter);
        prefix=field("Prefix: hex / \\xNN / b64:... / tekst"); body=field("Body / tag"); suffix=field("Suffix"); root.addView(prefix);root.addView(body);root.addView(suffix);
        arm=new CheckBox(this); arm.setText("ARM WRITES (ellers dry-run)"); arm.setTextColor(Color.WHITE); root.addView(arm);
        row(root,btn("PREVIEW",v->previewFrame()),btn("TAG / WRITE",v->tagWrite()));

        section(root,"Seeded body fuzz");
        seed=field("Seed, f.eks. 4201337"); seed.setText("4201337"); count=field("Cases (1–100)"); count.setText("16"); root.addView(seed);root.addView(count);
        row(root,btn("RUN BODY FUZZ",v->runFuzz()),btn("STOP FUZZ",v->stopFuzz()));
        root.addView(t("Standard: prefix/suffix beholdes, bare body muteres. Body må være eksplisitt satt. Standard SIG-identitetsfelt (2A00/2A23–2A29 osv.) er sperret fra fuzz. Aktiv fuzz krever ARM WRITES.",13));

        section(root,"Lab log");
        log=mono("Logg: "+new File(getExternalFilesDir(null),"bt-lab.jsonl").getAbsolutePath()+"\n"); root.addView(log);
        setContentView(sv);
    }

    private TextView t(String s,int sp){TextView v=new TextView(this);v.setText(s);v.setTextColor(Color.WHITE);v.setTextSize(sp);v.setPadding(4,8,4,8);return v;}
    private TextView mono(String s){TextView v=t(s,13);v.setTypeface(android.graphics.Typeface.MONOSPACE);v.setTextIsSelectable(true);return v;}
    private EditText field(String hint){EditText e=new EditText(this);e.setHint(hint);e.setHintTextColor(Color.GRAY);e.setTextColor(Color.WHITE);e.setSingleLine(false);return e;}
    private Button btn(String s, View.OnClickListener l){Button b=new Button(this);b.setText(s);b.setOnClickListener(l);return b;}
    private void row(LinearLayout p,View...vs){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);for(View v:vs)r.addView(v,new LinearLayout.LayoutParams(0,-2,1));p.addView(r);}
    private void section(LinearLayout p,String s){TextView v=t(s,22);v.setTextColor(Color.rgb(89,225,255));v.setPadding(4,28,4,8);p.addView(v);}

    private void requestBt(){
        if(Build.VERSION.SDK_INT>=31) requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT},7);
        else requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},7);
    }
    private boolean perms(){return Build.VERSION.SDK_INT<31 || (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED);}

    private final ScanCallback scanCb=new ScanCallback(){
        @Override public void onScanResult(int type,ScanResult r){BluetoothDevice d=r.getDevice();String key=(d.getName()==null?"(no name)":d.getName())+" | "+d.getAddress()+" | RSSI "+r.getRssi();runOnUiThread(()->{found.put(key,d);refreshDevices();});}
        @Override public void onScanFailed(int e){msg("scan_failed="+e);}
    };
    private void startScan(){
        if(!perms()){requestBt();return;} if(adapter==null||!adapter.isEnabled()){msg("Bluetooth er av");return;}
        stopScan(); found.clear();refreshDevices(); scanner=adapter.getBluetoothLeScanner();
        try{scanner.startScan(null,new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),scanCb);scanning=true;msg("SCAN start");h.postDelayed(this::stopScan,10000);}catch(Exception e){msg("scan error: "+e);}
    }
    private void stopScan(){if(scanning&&scanner!=null)try{scanner.stopScan(scanCb);}catch(Exception ignored){}scanning=false;}
    private void refreshDevices(){deviceAdapter.clear();deviceAdapter.addAll(found.keySet());deviceAdapter.notifyDataSetChanged();}
    private void connectSelected(){Object o=deviceSpin.getSelectedItem();if(o==null){msg("Velg enhet");return;}BluetoothDevice d=found.get(o.toString());if(d==null)return;disconnect();try{msg("CONNECT "+d.getName()+" "+d.getAddress());gatt=d.connectGatt(this,false,gattCb,BluetoothDevice.TRANSPORT_LE);}catch(Exception e){msg("connect error: "+e);}}
    private void disconnect(){stopFuzz();if(gatt!=null)try{gatt.disconnect();gatt.close();}catch(Exception ignored){}gatt=null;writable.clear();refreshChars();}

    private final BluetoothGattCallback gattCb=new BluetoothGattCallback(){
        @Override public void onConnectionStateChange(BluetoothGatt g,int status,int state){jlog("connection",kv("status",status,"state",state));if(state==BluetoothProfile.STATE_CONNECTED){msg("GATT connected; discoverServices");try{g.discoverServices();}catch(Exception e){msg("discover error "+e);}}else if(state==BluetoothProfile.STATE_DISCONNECTED){msg("GATT disconnected");stopFuzz();}}
        @Override public void onServicesDiscovered(BluetoothGatt g,int status){
            if(status!=BluetoothGatt.GATT_SUCCESS){msg("service discovery status="+status);return;}
            StringBuilder sb=new StringBuilder();writable.clear();nameChar=null;
            for(BluetoothGattService s:g.getServices()){
                sb.append("SERVICE ").append(sig(s.getUuid())).append("\n");
                jlog("gatt_service",kv("uuid",s.getUuid().toString(),"sig",sigName(s.getUuid()),"type",s.getType()));
                for(BluetoothGattCharacteristic c:s.getCharacteristics()){
                    String p=props(c);
                    sb.append("  ").append(sig(c.getUuid())).append(" [").append(p).append("]\n");
                    jlog("gatt_char",kv("service",s.getUuid().toString(),"uuid",c.getUuid().toString(),"sig",sigName(c.getUuid()),"props",p,"writable",isWritable(c),"fuzzProtected",protectedForFuzz(c)));
                    if(isWritable(c)){String key=s.getUuid()+" / "+c.getUuid()+" / "+sigName(c.getUuid());writable.put(key,c);}
                    if(shortId(c.getUuid()).equals("2a00"))nameChar=c;
                }
            }
            runOnUiThread(()->{map.setText(sb.toString());refreshChars();renamePlan();});
            jlog("gatt_map",kv("services",g.getServices().size(),"writable",writable.size(),"nameChar",nameChar!=null));
        }
        @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int status){WriteCase done=current;jlog("write_result",kv("case",done==null?"manual":done.id,"uuid",c.getUuid().toString(),"status",status));msg("WRITE status="+status+" "+sig(c.getUuid()));if(done!=null){current=null;h.postDelayed(MainActivity.this::nextWrite,240);}}
        @Override public void onCharacteristicRead(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value,int status){jlog("readback",kv("uuid",c.getUuid().toString(),"status",status,"hex",hex(value),"utf8",new String(value,StandardCharsets.UTF_8)));msg("READBACK "+sig(c.getUuid())+" = "+hex(value)+" | "+new String(value,StandardCharsets.UTF_8));}
        @SuppressWarnings("deprecation") @Override public void onCharacteristicRead(BluetoothGatt g,BluetoothGattCharacteristic c,int status){if(Build.VERSION.SDK_INT<33){byte[]v=c.getValue();jlog("readback",kv("uuid",c.getUuid().toString(),"status",status,"hex",hex(v),"utf8",new String(v==null?new byte[0]:v,StandardCharsets.UTF_8)));}}
    };

    private void refreshChars(){runOnUiThread(()->{charAdapter.clear();charAdapter.addAll(writable.keySet());charAdapter.notifyDataSetChanged();int i=firstSuggestedWritableIndex();if(i>=0)charSpin.setSelection(i);});}
    private int firstSuggestedWritableIndex(){int i=0;for(BluetoothGattCharacteristic c:writable.values()){if(!protectedForFuzz(c))return i;i++;}return writable.isEmpty()?-1:0;}
    private String props(BluetoothGattCharacteristic c){List<String>a=new ArrayList<>();int p=c.getProperties();if((p&2)!=0)a.add("read");if((p&8)!=0)a.add("write");if((p&4)!=0)a.add("writeNoRsp");if((p&16)!=0)a.add("notify");if((p&32)!=0)a.add("indicate");return String.join(",",a);}
    private boolean isWritable(BluetoothGattCharacteristic c){int p=c.getProperties();return (p&BluetoothGattCharacteristic.PROPERTY_WRITE)!=0||(p&BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)!=0;}
    private boolean protectedForFuzz(BluetoothGattCharacteristic c){
        String id=shortId(c.getUuid());
        if(id.isEmpty())return false;
        return id.equals("2a00")||id.equals("2a19")||id.equals("2a23")||id.equals("2a24")||id.equals("2a25")||id.equals("2a26")||id.equals("2a27")||id.equals("2a28")||id.equals("2a29");
    }

    private void renamePlan(){runOnUiThread(()->{String s;if(nameChar==null)s="Graffiti: 0x2A00 Device Name ikke synlig. Se etter vendor writable characteristic / app capture.";else if(isWritable(nameChar))s="Graffiti: 0x2A00 er writable. Persistent rename er en sterk kandidat. WRITE krever ARM WRITES; verifiser readback + reconnect + power-cycle + ny scan.";else s="Graffiti: 0x2A00 finnes, men er read-only. Ikke gjett. Lær vendor-protokollen fra captures.";msg(s);});}
    private void doRename(){String n=rename.getText().toString();if(n.isEmpty()){msg("Skriv navn");return;}if(nameChar==null||!isWritable(nameChar)){renamePlan();return;}byte[]v=n.getBytes(StandardCharsets.UTF_8);jlog("graffiti_rename_plan",kv("name",n,"hex",hex(v),"armed",arm.isChecked()));if(!arm.isChecked()){msg("DRY RUN rename: "+n+" / "+hex(v));return;}sendManual(nameChar,v,"rename");}

    private BluetoothGattCharacteristic selectedChar(){Object o=charSpin.getSelectedItem();return o==null?null:writable.get(o.toString());}
    private byte[] frame(){return join(parse(prefix.getText().toString()),parse(body.getText().toString()),parse(suffix.getText().toString()));}
    private void previewFrame(){byte[]p=parse(prefix.getText().toString()),b=parse(body.getText().toString()),s=parse(suffix.getText().toString()),f=join(p,b,s);msg("FRAME prefix="+hex(p)+" body="+hex(b)+" suffix="+hex(s)+"\nFULL="+hex(f)+"\nB64="+Base64.encodeToString(f,Base64.NO_WRAP));}
    private void tagWrite(){BluetoothGattCharacteristic c=selectedChar();if(c==null){msg("Velg writable characteristic");return;}byte[]f=frame();jlog("graffiti_tag_plan",kv("uuid",c.getUuid().toString(),"frame",hex(f),"armed",arm.isChecked()));if(f.length==0){msg("TAG avvist: frame er tom. Sett body/tag eller prefix/suffix først.");jlog("write_rejected_local",kv("case","tag-empty","uuid",c.getUuid().toString(),"reason","empty_frame"));return;}if(!arm.isChecked()){msg("DRY RUN TAG → "+sig(c.getUuid())+"\n"+hex(f));return;}sendManual(c,f,"tag");}

    private void runFuzz(){
        BluetoothGattCharacteristic c=selectedChar();if(c==null){msg("Velg writable characteristic");return;}
        if(protectedForFuzz(c)){msg("FUZZ SPERRET: "+sig(c.getUuid())+" er et standard identitets/statusfelt. Bruk Graffiti/rename eller velg vendor characteristic.");jlog("fuzz_rejected",kv("uuid",c.getUuid().toString(),"reason","protected_sig_characteristic"));return;}
        long sd;int n;try{sd=Long.parseLong(seed.getText().toString().trim());}catch(Exception e){sd=4201337;}try{n=Integer.parseInt(count.getText().toString().trim());}catch(Exception e){n=16;}n=Math.max(1,Math.min(100,n));
        byte[]pre=parse(prefix.getText().toString()),base=parse(body.getText().toString()),suf=parse(suffix.getText().toString());
        if(base.length==0){msg("FUZZ SPERRET: body er tom. Sett en kjent baseline/body først; prefix/suffix muteres ikke som standard.");jlog("fuzz_rejected",kv("uuid",c.getUuid().toString(),"reason","empty_body"));return;}
        Random r=new Random(sd);queue.clear();current=null;fuzzTarget=c;
        for(int i=0;i<n;i++){byte[]m=base.clone();int idx=r.nextInt(m.length);m[idx]=(byte)r.nextInt(256);byte[]f=join(pre,m,suf);String id=sd+"-"+String.format(Locale.US,"%03d",i);jlog("fuzz_case",kv("case",id,"seed",sd,"index",idx,"target",c.getUuid().toString(),"prefix",hex(pre),"body",hex(m),"suffix",hex(suf),"frame",hex(f),"armed",arm.isChecked()));if(arm.isChecked())queue.add(new WriteCase(id,"fuzz",sd,f));}
        if(!arm.isChecked()){fuzzTarget=null;msg("DRY RUN: genererte "+n+" cases. Se JSONL-logg.");return;}
        msg("ARMED fuzz: "+n+" cases → "+sig(c.getUuid())+", rate-limited");nextWrite();
    }
    private void stopFuzz(){queue.clear();current=null;fuzzTarget=null;msg("Fuzz queue cleared");}
    private void nextWrite(){if(current!=null||queue.isEmpty()||gatt==null)return;BluetoothGattCharacteristic c=fuzzTarget;if(c==null||protectedForFuzz(c)){queue.clear();fuzzTarget=null;return;}current=queue.poll();if(!write(c,current.data)){jlog("write_rejected_local",kv("case",current.id,"uuid",c.getUuid().toString()));current=null;h.postDelayed(this::nextWrite,240);}else if(queue.isEmpty()){h.postDelayed(()->{if(current==null)fuzzTarget=null;},600);}}

    private void sendManual(BluetoothGattCharacteristic c,byte[]v,String kind){jlog("write_submit",kv("kind",kind,"uuid",c.getUuid().toString(),"hex",hex(v)));if(write(c,v)&&c==nameChar&&(c.getProperties()&BluetoothGattCharacteristic.PROPERTY_READ)!=0)h.postDelayed(()->{try{gatt.readCharacteristic(c);}catch(Exception ignored){}},700);}
    @SuppressWarnings("deprecation") private boolean write(BluetoothGattCharacteristic c,byte[]v){if(gatt==null)return false;try{int wt=(c.getProperties()&BluetoothGattCharacteristic.PROPERTY_WRITE)!=0?BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT:BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;if(Build.VERSION.SDK_INT>=33)return gatt.writeCharacteristic(c,v,wt)==BluetoothStatusCodes.SUCCESS;c.setWriteType(wt);c.setValue(v);return gatt.writeCharacteristic(c);}catch(Exception e){msg("write exception "+e);return false;}}

    private byte[] parse(String x){x=x.trim();if(x.isEmpty())return new byte[0];try{if(x.regionMatches(true,0,"b64:",0,4))return Base64.decode(x.substring(4).replaceAll("\\s+",""),Base64.DEFAULT);if(x.matches("(?i)(\\\\x[0-9a-f]{2}\\s*)+")){String h=x.replaceAll("(?i)\\\\x","").replaceAll("\\s+","");return hexBytes(h);}String h=x.replaceAll("(?i)0x","").replaceAll("[\\s,:_-]+","");if(h.matches("(?i)[0-9a-f]+")&&h.length()%2==0)return hexBytes(h);}catch(Exception ignored){}return x.getBytes(StandardCharsets.UTF_8);}
    private byte[] hexBytes(String h){byte[]o=new byte[h.length()/2];for(int i=0;i<o.length;i++)o[i]=(byte)Integer.parseInt(h.substring(i*2,i*2+2),16);return o;}
    private byte[] join(byte[]...aa){int n=0;for(byte[]a:aa)n+=a.length;byte[]o=new byte[n];int p=0;for(byte[]a:aa){System.arraycopy(a,0,o,p,a.length);p+=a.length;}return o;}
    private String hex(byte[]v){if(v==null)return"";StringBuilder s=new StringBuilder();for(byte b:v){if(s.length()>0)s.append(' ');s.append(String.format(Locale.US,"%02x",b&255));}return s.toString();}

    private String shortId(UUID u){String s=u.toString().toLowerCase(Locale.US);return s.matches("0000[0-9a-f]{4}-0000-1000-8000-00805f9b34fb")?s.substring(4,8):"";}
    private String sig(UUID u){String n=sigName(u);return n.isEmpty()?u.toString():u+"  <"+n+">";}
    private String sigName(UUID u){switch(shortId(u)){case"1800":return"Generic Access";case"1801":return"Generic Attribute";case"180a":return"Device Information";case"180f":return"Battery Service";case"2a00":return"Device Name";case"2a19":return"Battery Level";case"2a23":return"System ID";case"2a24":return"Model Number";case"2a25":return"Serial Number";case"2a26":return"Firmware Revision";case"2a27":return"Hardware Revision";case"2a28":return"Software Revision";case"2a29":return"Manufacturer Name";default:return u.toString().equalsIgnoreCase("6e400001-b5a3-f393-e0a9-e50e24dcca9e")?"Nordic UART Service":u.toString().equalsIgnoreCase("6e400002-b5a3-f393-e0a9-e50e24dcca9e")?"NUS RX/write":u.toString().equalsIgnoreCase("6e400003-b5a3-f393-e0a9-e50e24dcca9e")?"NUS TX/notify":"";}}

    private Map<String,Object> kv(Object...x){Map<String,Object>m=new LinkedHashMap<>();for(int i=0;i+1<x.length;i+=2)m.put(String.valueOf(x[i]),x[i+1]);return m;}
    private void jlog(String event,Map<String,Object> fields){try{JSONObject o=new JSONObject();o.put("ts",System.currentTimeMillis());o.put("event",event);for(Map.Entry<String,Object>e:fields.entrySet())o.put(e.getKey(),e.getValue());File f=new File(getExternalFilesDir(null),"bt-lab.jsonl");try(FileWriter w=new FileWriter(f,true)){w.write(o.toString()+"\n");}}catch(Exception ignored){}}
    private void msg(String s){runOnUiThread(()->{if(log!=null)log.append(s+"\n");});}
}
