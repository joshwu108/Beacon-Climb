const fs=require('fs'),path=require('path'),yaml=require('yaml'),Ajv=require('ajv/dist/2020'),addFormats=require('ajv-formats'),RefParser=require('@apidevtools/json-schema-ref-parser');
(async()=>{
 const root=path.resolve(__dirname,'../..'),spec=yaml.parse(fs.readFileSync(path.join(root,'contracts/openapi.yaml'),'utf8'));
 const deref=await RefParser.dereference(spec,{dereference:{circular:false}});
 const ajv=new Ajv({strict:false,allErrors:true});addFormats(ajv);
 let count=0;
 for(const [url,item] of Object.entries(deref.paths)) for(const [method,operation] of Object.entries(item)){
  if(operation['x-planned'])continue;
  const dir=path.join(root,'contracts/examples',operation.operationId);
  if(!fs.existsSync(dir))throw Error('missing examples: '+operation.operationId);
  const req=JSON.parse(fs.readFileSync(path.join(dir,'request.json')));
  if(req.method!==method.toUpperCase()||req.path!==url)throw Error('request method/path mismatch '+operation.operationId);
  for(const p of operation.parameters||[]){const bag=req[p.in==='path'?'path_params':p.in==='header'?'headers':p.in]||{};if(p.required && !(p.name in bag))throw Error('missing parameter '+operation.operationId+' '+p.name);if(p.name in bag && !ajv.validate(p.schema,bag[p.name]))throw Error('invalid parameter '+operation.operationId+' '+p.name+': '+ajv.errorsText());}
  if(operation.requestBody){if(!('body'in req))throw Error('missing request body '+operation.operationId);const schema=operation.requestBody.content['application/json'].schema;if(!ajv.validate(schema,req.body))throw Error('invalid request '+operation.operationId+': '+ajv.errorsText());}
  for(const file of fs.readdirSync(dir).filter(x=>/^request-(409|422)(?:-future)?\.json$/.test(x))){
   const special=JSON.parse(fs.readFileSync(path.join(dir,file)));
   if(special.method!==method.toUpperCase()||special.path!==url)throw Error('special request route mismatch '+file);
   const code=String(special.expected_status);if(!operation.responses[code]||!fs.existsSync(path.join(dir,file.replace(/^request/,'response'))))throw Error('missing paired response '+operation.operationId+' '+code);
   let valid=true;
   for(const p of operation.parameters||[]){const bag=special[p.in==='path'?'path_params':p.in==='header'?'headers':p.in]||{};if(p.required&&!(p.name in bag))valid=false;else if(p.name in bag&&!ajv.validate(p.schema,bag[p.name]))valid=false;}
   if(operation.requestBody&&!ajv.validate(operation.requestBody.content['application/json'].schema,special.body))valid=false;
   if(code==='422'&&valid&&special.expected_validation!=='semantic')throw Error('422 fixture must fail schema validation '+operation.operationId);
   if(code==='409'&&!valid)throw Error('409 fixture must be schema-valid '+operation.operationId);
   count++;
  }
  for(const file of fs.readdirSync(dir).filter(x=>x.startsWith('response')&&x.endsWith('.json'))){
   const res=JSON.parse(fs.readFileSync(path.join(dir,file)));const declared=operation.responses[String(res.status)];if(!declared)throw Error('undeclared status '+operation.operationId+' '+res.status);
   if(!res.headers||!res.headers['X-Request-Id'])throw Error('missing X-Request-Id '+operation.operationId);
   const media=declared.content?.['application/json'];if(media){if(!('body'in res))throw Error('missing response body '+file);if(!ajv.validate(media.schema,res.body))throw Error('invalid response '+operation.operationId+' '+file+': '+ajv.errorsText());}else if('body'in res)throw Error('unexpected response body '+file);
   count++;
  }
  count++;
 }
 const decisionSchema=JSON.parse(fs.readFileSync(path.join(root,'contracts/decisions/decision-record.v1.schema.json')));
 const validateDecision=ajv.compile(decisionSchema);
 for(const file of ['synthetic-snapshot.json','synthetic-decision.json']){const data=JSON.parse(fs.readFileSync(path.join(root,'contracts/decisions/examples',file)));if(!validateDecision(data))throw Error('invalid decision '+file+': '+ajv.errorsText(validateDecision.errors));count++;}
 const crypto=require('node:crypto'),assert=require('node:assert/strict');
 const ex=path.join(root,'contracts/decisions/examples');
 const snapBytes=fs.readFileSync(path.join(ex,'synthetic-snapshot.json'));
 const snapshot=JSON.parse(snapBytes),record=JSON.parse(fs.readFileSync(path.join(ex,'synthetic-decision.json')));
 const sha=b=>crypto.createHash('sha256').update(b).digest('hex');
 assert.equal(sha(snapBytes),record.candidate_set_digest,'candidate digest');
 assert.equal(record.candidate_snapshot_uri,`data/decisions/${record.candidate_set_digest.slice(0,2)}/${record.candidate_set_digest}.json`,'candidate URI');
 assert.equal(sha(fs.readFileSync(path.join(ex,'heuristic-v1.cjs'))),record.policy_code_digest,'policy digest');
 assert.equal(sha(fs.readFileSync(path.join(ex,'synthetic-environment.json'))),record.environment_digest,'environment digest');
 assert.equal(sha(`${record.policy_version}|${record.user_id}|${record.session_id}`),record.rng_seed,'seed');
 assert.deepEqual(require(path.join(ex,'heuristic-v1.cjs')).rank(snapshot,record),record.ranked_results,'faithful fixture ranks');
 console.log(`all examples valid (${count} request/response/decision fixtures)`);
})().catch(e=>{console.error(e.stack||e);process.exit(1)});
